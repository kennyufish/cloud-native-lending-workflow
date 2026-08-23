# Architecture notes

This document records the small set of decisions that make the workflow useful as a portfolio system without turning it into a speculative production platform.

## Service ownership

`application-service` owns the application aggregate and the durable workflow state. Its database tables are the source of truth for the public status and audit timeline. It also owns the transactional outbox and the decision inbox (`processed_events`).

`decision-worker` owns neither application state nor database credentials. It consumes the versioned event, applies a deterministic synthetic policy, and calls the narrow internal decision callback. Keeping that boundary explicit makes the asynchronous behavior testable and makes a future real decision provider replaceable without adding a third service to this demo.

## Event and state boundaries

The `APPLICATION_SUBMITTED` event is versioned with `schemaVersion: 1`. The event body contains only fictional workflow data needed by the demo policy. The SQS message also carries the event type and W3C `traceparent` as message attributes.

The initial application insert, outbox insert, and submission audit row are one PostgreSQL transaction. A publisher later claims and sends the outbox row. The database transaction does not hold an SQS network call open. If the process crashes after SQS accepts the message but before the published marker is committed, the event may be sent again; the worker's decision inbox makes that duplicate safe.

The callback transaction inserts the event ID into `processed_events`, changes `SUBMITTED` to `OFFERED` or `DECLINED`, records notification status, and appends the resulting audit rows. A duplicate callback with the same event hash returns the current view. A reused event ID with a different payload is rejected as a conflict.

## Delivery and retry semantics

The queue contract is at-least-once. The worker does not delete a message until the callback returns successfully. Any parsing, policy, transport, or callback failure leaves the message for SQS retry. Once the queue's redrive receive limit is reached, SQS moves the message to the DLQ for inspection.

This is intentionally not exactly-once processing. The design goal is idempotent business effects across duplicate deliveries, not a claim that a distributed system can eliminate every duplicate at the acknowledgement boundary.

The outbox publisher uses a short lease token and `FOR UPDATE SKIP LOCKED` so multiple publisher ticks can coexist without holding a database lock while waiting on SQS. A failed send releases the lease after a small delay and increments a failure metric. A production implementation would normally add a bounded backoff policy, a pending-age metric, and an operational replay/quarantine procedure; the demo keeps the publisher compact and visible.

## Trace propagation

The public request span is captured into the outbox record. The publisher writes the W3C `traceparent` into the SQS message attributes. The worker extracts it and starts a consumer span, then the callback HTTP client continues that trace. This preserves a causal view across the HTTP → database/outbox → SQS → worker → HTTP callback path. Locally, the OpenTelemetry Collector forwards traces to Tempo; Prometheus scrapes the services' Actuator endpoints directly.

The trace ID is also written into audit records so an application timeline can be correlated with an observability backend. This is correlation metadata, not a substitute for access control or a tamper-proof compliance ledger.

## Why only two services?

The public API and the asynchronous worker are the two independently interesting runtime boundaries in this exercise. Splitting notification or audit into more services would add deployment and failure modes without demonstrating a currently missing capability. Notification is represented as a durable state transition and audit event, and can be extracted later if a real delivery provider becomes part of the scope.

## AWS topology decisions

Terraform describes ECS/Fargate tasks, an ALB for the public API, RDS PostgreSQL in private subnets, SQS with a DLQ, ECR, IAM task roles, CloudWatch logs, and queue alarms. The demo uses a low-cost networking option for learning: tasks may receive public IPs to avoid a NAT gateway, while RDS remains private. That is clearly a demo trade-off, not a production recommendation.

The public listener forwards application API routes. The worker calls the application service through private service discovery/internal networking in the Terraform topology; the `/internal/v1/decisions` callback is not intended to be a public client endpoint. The shared callback token is suitable only for a synthetic demo and should be replaced with workload identity and a stronger authorization boundary for real deployments.

## Invariants worth testing

1. One idempotency key and one request hash produce one application and one outbox event.
2. The same key with a different request is a conflict.
3. A decision event can be applied once; the same event can be replayed safely.
4. A failed callback does not delete its SQS message.
5. A repeatedly failing message eventually appears in the DLQ.
6. A supplied trace context reaches the worker and is visible in audit correlation metadata.
7. Audit rows cannot be updated or deleted through the database schema's trigger.

The integration tests provide executable evidence for the first six invariants. The audit immutability trigger is a database-level guard and should remain covered by a future focused migration test if the schema grows.
