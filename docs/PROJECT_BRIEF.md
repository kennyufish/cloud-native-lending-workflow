# Project brief

## Portfolio objective

Extend an existing Java/REST/SQL/FinTech profile with a compact, reviewable demonstration of cloud-native distributed-system concerns: asynchronous delivery, operationally safe retries, cloud resource modeling, trace context, and reproducible integration tests.

## Scope

The workflow has two services and one durable event:

```text
application-service ── PostgreSQL transaction ──> outbox_events
outbox publisher ── SQS application-events ──> decision-worker
decision-worker ── internal callback ──> application-service
application-service ── audit_records + notification state
```

The data and policy are fictional. The selected scope is intentionally large enough to expose delivery and observability boundaries but small enough to run on a laptop and destroy after an AWS experiment.

## Evidence map

| Resume gap | Repository evidence |
| --- | --- |
| Cloud runtime | Terraform ECS/Fargate, ALB, RDS, ECR, IAM |
| Event-driven systems | SQS event, long polling, trace attributes, callback boundary |
| Reliability | PostgreSQL outbox, leases, idempotency key, event inbox, retry and DLQ integration tests |
| Observability | Actuator/Prometheus metrics, OpenTelemetry Collector, Tempo, Grafana, trace IDs in audit rows |
| Delivery automation | Maven, Docker Compose smoke test, GitHub Actions CI, manual OIDC deploy/destroy |
| Integration testing | Testcontainers PostgreSQL + LocalStack and Awaitility-driven asynchronous assertions |

## Honest positioning

Use verbs such as `built`, `implemented`, `instrumented`, `tested`, and `defined` for this repository. Do not say `deployed`, `operated`, `scaled`, `achieved exactly-once`, `processed production volume`, or `reduced AWS cost` unless separate evidence exists outside this project. A local Testcontainers run demonstrates behavior under a reproducible dependency environment; it is not a production capacity benchmark.

## Suggested interview walkthrough

1. Show the idempotent POST and explain why the request body is hashed.
2. Show the outbox row and explain why the database transaction does not call SQS.
3. Follow the event through the queue, consumer span, callback, and audit timeline.
4. Make the callback fail and show that the worker does not delete the message.
5. Show the DLQ integration test and explain the at-least-once contract.
6. Open the Terraform plan and point out the cost trade-off and destroy path.
7. State the limits: synthetic policy, shared demo token, no AWS deployment claim, and no exactly-once guarantee.
