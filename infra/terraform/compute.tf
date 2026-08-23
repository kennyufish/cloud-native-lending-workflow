resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  depends_on = [aws_cloudwatch_log_group.container_insights]

  tags = { Name = local.name }
}

resource "aws_service_discovery_service" "application" {
  name = "application-service"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"

    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  tags = { Name = "${local.name}-application-service" }
}

resource "aws_ecs_task_definition" "application" {
  family                   = "${local.name}-application"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.application_task.arn

  container_definitions = jsonencode([
    {
      name      = "application-service"
      image     = local.application_image
      essential = true

      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SERVER_PORT", value = "8080" },
        { name = "DATABASE_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:5432/${var.db_name}" },
        { name = "DATABASE_USERNAME", value = var.db_username },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "APPLICATION_EVENTS_QUEUE_URL", value = aws_sqs_queue.application_events.url },
        { name = "MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED", value = "false" },
        { name = "OTEL_SERVICE_NAME", value = "application-service" }
      ]

      secrets = [
        {
          name      = "DATABASE_PASSWORD"
          valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::"
        },
        {
          name      = "INTERNAL_API_TOKEN"
          valueFrom = "${aws_secretsmanager_secret.internal_api_token.arn}:::${aws_secretsmanager_secret_version.internal_api_token.version_id}"
        }
      ]

      healthCheck = {
        command     = ["CMD-SHELL", "curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.application.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])

  tags = { Name = "${local.name}-application" }
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker_task.arn

  container_definitions = jsonencode([
    {
      name      = "decision-worker"
      image     = local.worker_image
      essential = true

      portMappings = [
        {
          containerPort = 8081
          hostPort      = 8081
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SERVER_PORT", value = "8081" },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "APPLICATION_EVENTS_QUEUE_URL", value = aws_sqs_queue.application_events.url },
        { name = "APPLICATION_SERVICE_URL", value = local.application_service_url },
        { name = "SQS_WAIT_TIME_SECONDS", value = "20" },
        { name = "MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED", value = "false" },
        { name = "OTEL_SERVICE_NAME", value = "decision-worker" }
      ]

      secrets = [
        {
          name      = "INTERNAL_API_TOKEN"
          valueFrom = "${aws_secretsmanager_secret.internal_api_token.arn}:::${aws_secretsmanager_secret_version.internal_api_token.version_id}"
        }
      ]

      healthCheck = {
        command     = ["CMD-SHELL", "curl --fail --silent http://127.0.0.1:8081/actuator/health/readiness || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.worker.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])

  tags = { Name = "${local.name}-worker" }
}

resource "aws_ecs_service" "application" {
  name            = "application-service"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.application.arn
  desired_count   = var.application_desired_count
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.application.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.application.arn
    container_name   = "application-service"
    container_port   = 8080
  }

  service_registries {
    registry_arn   = aws_service_discovery_service.application.arn
    container_name = "application-service"
    container_port = 8080
  }

  depends_on = [aws_lb_listener.application]

  tags = { Name = "${local.name}-application" }
}

resource "aws_ecs_service" "worker" {
  name            = "decision-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = true
  }

  depends_on = [aws_ecs_service.application]

  tags = { Name = "${local.name}-worker" }
}
