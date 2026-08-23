locals {
  name = "${var.project_name}-${var.environment}"

  tags = merge(
    {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      CostModel   = "disposable-demo"
    },
    var.tags
  )

  application_image = coalesce(var.application_image, "${aws_ecr_repository.application.repository_url}:latest")
  worker_image      = coalesce(var.worker_image, "${aws_ecr_repository.worker.repository_url}:latest")
  availability_zones = var.availability_zones == null ? slice(
    data.aws_availability_zones.available.names,
    0,
    2
  ) : var.availability_zones

  application_service_url = "http://application-service.${aws_service_discovery_private_dns_namespace.this.name}:8080"
}
