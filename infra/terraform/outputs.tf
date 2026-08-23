output "application_url" {
  description = "Public HTTP URL for application-service through the ALB."
  value       = "http://${aws_lb.application.dns_name}"
}

output "application_events_queue_url" {
  description = "SQS Standard queue consumed by decision-worker."
  value       = aws_sqs_queue.application_events.url
}

output "application_events_dlq_url" {
  description = "SQS DLQ for poison or repeatedly failing events."
  value       = aws_sqs_queue.application_events_dlq.url
}

output "application_events_queue_arn" {
  description = "SQS Standard queue ARN for IAM or diagnostics."
  value       = aws_sqs_queue.application_events.arn
}

output "application_ecr_repository_url" {
  description = "ECR repository for application-service images."
  value       = aws_ecr_repository.application.repository_url
}

output "worker_ecr_repository_url" {
  description = "ECR repository for decision-worker images."
  value       = aws_ecr_repository.worker.repository_url
}

output "rds_endpoint" {
  description = "Private RDS endpoint; reachable only from application-service security group."
  value       = aws_db_instance.this.address
}

output "rds_master_secret_arn" {
  description = "RDS-managed Secrets Manager secret ARN. The password is intentionally never output."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
  sensitive   = true
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "cloud_map_namespace" {
  description = "Private DNS namespace used by decision-worker to resolve application-service."
  value       = aws_service_discovery_private_dns_namespace.this.name
}
