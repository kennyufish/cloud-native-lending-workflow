resource "aws_sqs_queue" "application_events_dlq" {
  name                      = "${local.name}-application-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
  receive_wait_time_seconds = 20

  tags = { Name = "${local.name}-application-events-dlq" }
}

resource "aws_sqs_queue" "application_events" {
  name                       = "${local.name}-application-events"
  visibility_timeout_seconds = 120
  message_retention_seconds  = 86400
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.application_events_dlq.arn
    maxReceiveCount     = 5
  })

  tags = { Name = "${local.name}-application-events" }
}

resource "aws_sqs_queue_redrive_allow_policy" "application_events_dlq" {
  queue_url = aws_sqs_queue.application_events_dlq.url

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.application_events.arn]
  })
}
