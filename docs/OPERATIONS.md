# Operations runbook

This is a demo runbook for synthetic data. It describes how to exercise and clean up the local stack and how the manual AWS workflows are intended to be reviewed. It does not claim a production SLO, an AWS deployment, or a compliance-ready incident process.

## Local startup and smoke test

From the repository root, either start the stack directly:

```bash
docker compose up --build --wait
```

or let the smoke script start and clean it up automatically:

```powershell
pwsh -File .\scripts\smoke-test.ps1
```

When invoked without `-KeepServices`, a successful smoke run removes its stack and named volumes. The smoke test should demonstrate:

- `POST /api/v1/applications` returns a created application.
- The same `Idempotency-Key` and payload replay the original application.
- The asynchronous worker eventually records `OFFERED` or `DECLINED`.
- The response includes the audit timeline and simulated notification state.
- A malformed event can be redriven to the DLQ.
- Actuator metrics are reachable.

If the stack is already running and a reviewer wants to inspect the services manually:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8080/actuator/prometheus
```

Grafana is available at `http://localhost:3000`; the local development credentials are `admin` / `local-demo` unless changed in Compose (anonymous read-only access is enabled for local inspection). Prometheus is at `http://localhost:9090`, and Tempo is at `http://localhost:3200`.

## Common local failure signals

| Signal | First checks | Expected action |
| --- | --- | --- |
| Application remains `SUBMITTED` | `docker compose logs decision-worker`; inspect LocalStack queue | Confirm worker can reach SQS and the callback URL; the message should remain available for retry if the callback is down |
| Outbox publish failures | `docker compose logs application-service`; `lending_outbox_publish_failures_total` | Restore LocalStack/SQS connectivity; the row remains pending and will be retried |
| Messages in the DLQ | LocalStack queue attributes; worker failure logs | Inspect the message body and receive count; fix the deterministic failure, then replay only synthetic messages deliberately |
| No traces in Grafana | Collector logs; service `OTEL_*` environment | Confirm the Collector is healthy and both services use its Compose hostname; metric/API behavior is independent of tracing |
| Testcontainers cannot start | Docker daemon and image availability | Start Docker and rerun `./mvnw.cmd verify`; do not convert integration tests into mocks just to bypass the environment |

The exact metric names are Prometheus-normalized versions of the custom meters. Search the `/actuator/prometheus` output for `lending_outbox`, `lending_decision`, and `lending_` when diagnosing a local run.

## Retry and DLQ drill

The worker deletes a message only after a successful callback. To verify the failure boundary, stop or make the application callback unavailable, then publish a synthetic event into the main queue. Observe the worker log as the receive count increases and confirm that the message eventually appears in the DLQ after the configured redrive limit.

The callback defaults to a 1-second connect timeout and a 3-second read timeout. Keep `CALLBACK_CONNECT_TIMEOUT` and `CALLBACK_READ_TIMEOUT` below the SQS visibility timeout so a stalled application service cannot block a worker indefinitely.

Do not replay a DLQ message blindly. First inspect the payload, identify whether the failure is a malformed event, a transient dependency, or a code defect, and preserve the original message ID and trace context when recording the drill result. The local Compose environment is disposable; a production replay tool would need authorization, rate limiting, and a separate audit record.

## AWS preflight

The manual deploy workflow expects:

1. An AWS account and a region selected in repository/environment variables.
2. A GitHub `demo` Environment with required reviewers if the repository policy requires approval.
3. An IAM role trusted through GitHub Actions OIDC, limited to the demo resources and the repository/branch or environment subject, stored as the `AWS_DEPLOY_ROLE_ARN` Environment secret.
4. A versioned/encrypted S3 Terraform state bucket, exposed to Actions as the `TF_STATE_BUCKET` Environment variable. `TF_STATE_KEY` is optional and defaults to `lending-workflow/demo/terraform.tfstate`.
5. The `AWS_REGION` Environment variable and an `INTERNAL_API_TOKEN` Environment secret for the private callback. The workflow passes the same region to AWS credentials, the state backend, and Terraform; Terraform selects two available zones unless explicitly overridden.
6. ECR, ECS, RDS, ALB, SQS, CloudWatch, and IAM permissions sufficient for the Terraform plan and image push. ECR access must include `ecr:BatchGetImage` so reruns can safely reuse an existing immutable commit tag.
7. Review of all Terraform variables, especially the VPC CIDR, DB sizing, deletion behavior, and public task IP trade-off.

The workflow uses short-lived OIDC credentials and does not require long-lived AWS keys in GitHub secrets. The operator must inspect the plan and deliberately confirm the `DEPLOY` or `DESTROY` input. This checkout has not executed either workflow against AWS.

## Observability review

For a local review, correlate:

1. The HTTP submission span in Grafana/Tempo.
2. The SQS consumer span in `decision-worker`.
3. The callback span back to `application-service`.
4. The trace ID returned in the application's audit timeline.
5. Prometheus gauges/counters for pending outbox rows, outbox publish failures, decision failures, and processing latency.

For the Terraform demo topology, CloudWatch alarms focus on signals with direct operational meaning: visible DLQ messages and queue age/backlog. Container logs use short retention to limit the cost of an experiment. A production service would add alert ownership, runbook links, latency/error budgets, secret rotation, dashboard-as-code review, and a deliberate data retention policy.

## Cleanup and cost control

Stop local services when finished:

```bash
docker compose down --volumes
```

The volume flag removes only the synthetic PostgreSQL/observability data for this Compose project. If a named volume is intentionally retained for inspection, omit `--volumes` and remove it later after confirming its name with `docker volume ls`.

For AWS, use the manual destroy workflow after reviewing its plan:

1. Select the `demo` GitHub Environment.
2. Enter the exact `DESTROY` confirmation.
3. Review the destroy plan, especially the RDS deletion behavior.
4. Run the destroy operation and confirm the stack is gone.

The demo Terraform configuration is designed to avoid a permanent idle environment, but billing can still occur while resources exist and state/log artifacts may have separate retention. Destroying an environment is an intentional destructive action; retain a state backup only when needed for the exercise and never place applicant PII in it.

## Boundaries

The runbook does not cover customer data, real credit decisions, regulatory reporting, production paging, disaster recovery RTO/RPO, or an AWS account's organization-wide guardrails. Those are deliberately outside this portfolio project's evidence boundary.
