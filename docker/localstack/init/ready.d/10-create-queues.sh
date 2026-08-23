#!/usr/bin/env bash
set -euo pipefail

region="${AWS_DEFAULT_REGION:-us-west-2}"
main_queue="application-events"
dead_letter_queue="application-events-dlq"

awslocal --region "$region" sqs create-queue \
  --queue-name "$dead_letter_queue" \
  --attributes VisibilityTimeout=5,MessageRetentionPeriod=86400 \
  >/dev/null

dead_letter_url="$(awslocal --region "$region" sqs get-queue-url \
  --queue-name "$dead_letter_queue" --query QueueUrl --output text)"
dead_letter_arn="$(awslocal --region "$region" sqs get-queue-attributes \
  --queue-url "$dead_letter_url" --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)"

queue_attributes="$(python -c 'import json,sys; arn=sys.argv[1]; print(json.dumps({"VisibilityTimeout":"5","ReceiveMessageWaitTimeSeconds":"10","MessageRetentionPeriod":"86400","RedrivePolicy":json.dumps({"deadLetterTargetArn":arn,"maxReceiveCount":"3"})}))' "$dead_letter_arn")"
awslocal --region "$region" sqs create-queue \
  --queue-name "$main_queue" \
  --attributes "$queue_attributes" \
  >/dev/null

echo "Created $main_queue with $dead_letter_queue (maxReceiveCount=3)"
