resource "aws_cloudwatch_log_group" "application" {
  name              = "/ecs/${local.name}/application-service"
  retention_in_days = 7

  tags = { Name = "/ecs/${local.name}/application-service" }
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name}/decision-worker"
  retention_in_days = 7

  tags = { Name = "/ecs/${local.name}/decision-worker" }
}

resource "aws_cloudwatch_log_group" "rds_postgresql" {
  name              = "/aws/rds/instance/${local.name}/postgresql"
  retention_in_days = 7

  tags = { Name = "/aws/rds/instance/${local.name}/postgresql" }
}

resource "aws_cloudwatch_log_group" "container_insights" {
  name              = "/aws/ecs/containerinsights/${local.name}/performance"
  retention_in_days = 7

  tags = { Name = "/aws/ecs/containerinsights/${local.name}/performance" }
}
