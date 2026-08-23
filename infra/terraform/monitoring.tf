resource "aws_cloudwatch_metric_alarm" "dead_letter_messages" {
  alarm_name          = "${local.name}-dead-letter-messages"
  alarm_description   = "A decision event was moved to the DLQ; inspect before replaying."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.application_events_dlq.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  tags = { Name = "${local.name}-dead-letter-messages" }
}

resource "aws_cloudwatch_metric_alarm" "queue_age" {
  alarm_name          = "${local.name}-queue-age"
  alarm_description   = "The oldest application event is waiting longer than five minutes."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.application_events.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 300
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  tags = { Name = "${local.name}-queue-age" }
}

resource "aws_cloudwatch_metric_alarm" "application_running_tasks" {
  alarm_name        = "${local.name}-application-running-tasks"
  alarm_description = "Application-service has no running task."
  namespace         = "ECS/ContainerInsights"
  metric_name       = "RunningTaskCount"
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.application.name
  }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"

  tags = { Name = "${local.name}-application-running-tasks" }
}

resource "aws_cloudwatch_metric_alarm" "worker_running_tasks" {
  alarm_name        = "${local.name}-worker-running-tasks"
  alarm_description = "Decision-worker has no running task."
  namespace         = "ECS/ContainerInsights"
  metric_name       = "RunningTaskCount"
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.worker.name
  }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"

  tags = { Name = "${local.name}-worker-running-tasks" }
}
