# Disposable AWS demo infrastructure

This directory provisions the cloud shape for the two-service synthetic lending workflow:

```text
Internet -> public ALB -> application-service (ECS/Fargate)
                              |                |
                         private RDS       SQS Standard -> decision-worker (ECS/Fargate)
                              ^                |
                              +---- Cloud Map private callback
```

The stack is intentionally a low-cost learning/demo topology, not a production baseline:

- ECS tasks and the ALB run in public subnets with public IPs, and there is no NAT gateway.
- RDS is private, encrypted, single-AZ, and has no backup retention. RDS generates its master password in Secrets Manager.
- SQS is Standard, server-side encrypted with the AWS-managed key, long-polls for 20 seconds, and redrives after five receives to a DLQ.
- ECR repositories scan images on push, use immutable tags, and expire old images. `force_delete = true` makes `terraform destroy` remove demo images too.
- ECS application logs, RDS PostgreSQL exports, and Container Insights logs are retained for seven days. The alarms have no notification action; wire them to an SNS/PagerDuty integration for a real environment.
- `application-service` is the only ALB target. `/internal*` and `/actuator*` receive a fixed 404; the worker reaches the callback through private Cloud Map DNS and a security-group rule.
- The callback token is injected from a pinned Secrets Manager version rather than stored in the ECS task definition. Updating it creates new task-definition revisions for both services. Its value is still managed by Terraform, so remote state must remain encrypted and tightly restricted.

## Prerequisites

1. An AWS account and a region with at least two available zones. Terraform selects two automatically unless `availability_zones` is explicitly set.
2. Terraform 1.10+ and AWS provider 6.x. Terraform 1.10 is required for native S3 lockfiles.
3. A pre-created S3 state bucket with versioning and encryption enabled. This stack does not create its own backend. Copy `backend.hcl.example`, edit it, and initialize with:

   ```powershell
   terraform init -backend-config=backend.hcl
   ```

   The S3 backend uses native lockfiles (`use_lockfile = true`); the CI role needs the S3 state read/write and lock-file permissions documented by the Terraform S3 backend.

4. For GitHub Actions, use short-lived AWS OIDC credentials rather than access keys. The IAM role trust policy should require:

   - federated principal `token.actions.githubusercontent.com`;
   - audience `sts.amazonaws.com`;
   - a `sub` restricted to this repository and the protected `demo` environment.

   The workflow needs `id-token: write`, `contents: read`, the role ARN as a GitHub environment secret, and `ecr:BatchGetImage` alongside the ECR push permissions so reruns can detect an existing immutable tag. Do not put AWS keys in repository secrets, tfvars, logs, or the state bucket name itself.

5. Build and push immutable `sha-*` image tags to the two ECR repositories. The workflow reuses an existing tag when the same commit is rerun. A first deployment can create only the ECR repositories, push the images, and then apply the ECS services. If the default `:latest` image is used before an image exists, ECS tasks cannot start.

## Plan/apply

```powershell
Copy-Item terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars; keep real secrets out of git
terraform fmt -recursive
terraform validate
terraform plan -out demo.tfplan
terraform apply demo.tfplan
terraform output application_url
```

The only intentional secret input is `internal_api_token`; both it and the RDS-managed password are delivered to tasks through Secrets Manager. Terraform state contains the callback token value, so use an encrypted, versioned, access-restricted remote backend. Never commit `terraform.tfvars`, plan files, or state.

## Destroy and cost control

This is a synthetic, disposable demo. When finished, inspect the plan and run:

```powershell
terraform plan -destroy -out destroy.tfplan
terraform apply destroy.tfplan
# or: terraform destroy
```

`skip_final_snapshot`, `deletion_protection = false`, zero RDS backup retention, seven-day logs, and ECR `force_delete` are deliberate demo choices. They reduce cleanup friction but are unsafe defaults for real lending data. Confirm the state/workspace points only at this demo before destroying; destroy removes the RDS instance, queue contents, task logs, ECR images, and the ALB.

## Operational caveats

- This stack has not been applied to AWS in this repository. Validate credentials, quotas, image availability, AZs, and account policies in a sandbox account before any apply.
- No SNS actions are attached to alarms to avoid creating an always-on notification dependency. The DLQ alarm and queue-age alarm are still useful in CloudWatch.
- Cloud Map is service discovery, not an application authentication boundary. The callback token and security group are both required. Applying a changed token pins the new secret version into both task definitions and rolls both services; production rotation would use a managed dual-token or workload-identity procedure.
- The public-subnet ECS tradeoff is for a small demo. A production layout should place tasks in private subnets, provide controlled egress through NAT or VPC endpoints, use TLS at the ALB, and use a dedicated KMS key and backup policy.
