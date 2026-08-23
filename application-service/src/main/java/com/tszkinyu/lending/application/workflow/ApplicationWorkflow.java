package com.tszkinyu.lending.application.workflow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class ApplicationWorkflow {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TraceHeaders traceHeaders;
    private final Tracer tracer;

    ApplicationWorkflow(JdbcClient jdbc, ObjectMapper objectMapper, TraceHeaders traceHeaders, Tracer tracer) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.traceHeaders = traceHeaders;
        this.tracer = tracer;
    }

    @Transactional
    Submission submit(String idempotencyKey, ApplicationController.SubmissionRequest request) {
        String requestHash = hash(request);
        UUID applicationId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        OffsetDateTime databaseTimestamp = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        int inserted = jdbc.sql("""
                        INSERT INTO applications (
                            id, idempotency_key, request_hash, applicant_reference,
                            credit_score, annual_income, requested_amount, status, created_at, updated_at
                        ) VALUES (
                            :id, :idempotencyKey, :requestHash, :applicantReference,
                            :creditScore, :annualIncome, :requestedAmount, 'SUBMITTED', :createdAt, :createdAt
                        )
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .params(Map.of(
                        "id", applicationId,
                        "idempotencyKey", idempotencyKey,
                        "requestHash", requestHash,
                        "applicantReference", request.applicantReference(),
                        "creditScore", request.creditScore(),
                        "annualIncome", request.annualIncome(),
                        "requestedAmount", request.requestedAmount(),
                        "createdAt", databaseTimestamp))
                .update();

        if (inserted == 0) {
            StoredApplication existing = findByIdempotencyKey(idempotencyKey);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency-Key was already used for a different request");
            }
            return new Submission(existing.id(), existing.status(), true, existing.createdAt());
        }

        UUID eventId = UUID.randomUUID();
        String traceparent = traceHeaders.capture();
        ApplicationSubmittedEvent event = new ApplicationSubmittedEvent(
                eventId,
                applicationId,
                "APPLICATION_SUBMITTED",
                1,
                request.applicantReference(),
                request.creditScore(),
                request.annualIncome(),
                request.requestedAmount(),
                createdAt);
        jdbc.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, event_type, payload, traceparent, created_at
                        ) VALUES (:id, :aggregateId, :eventType, CAST(:payload AS jsonb), :traceparent, :createdAt)
                        """)
                .params(Map.of(
                        "id", eventId,
                        "aggregateId", applicationId,
                        "eventType", event.eventType(),
                        "payload", json(event),
                        "traceparent", traceparent == null ? "" : traceparent,
                        "createdAt", databaseTimestamp))
                .update();
        jdbc.sql("""
                        INSERT INTO audit_records (application_id, event_id, type, details, trace_id, created_at)
                        VALUES (:applicationId, :eventId, 'APPLICATION_SUBMITTED', CAST(:details AS jsonb),
                                :traceId, :createdAt)
                        """)
                .params(Map.of(
                        "applicationId", applicationId,
                        "eventId", eventId,
                        "details", "{\"status\":\"SUBMITTED\"}",
                        "traceId", currentTraceId(),
                        "createdAt", databaseTimestamp))
                .update();
        return new Submission(applicationId, "SUBMITTED", false, createdAt);
    }

    @Transactional
    ApplicationView recordDecision(DecisionController.DecisionRequest decision) {
        validateDecision(decision);
        String decisionHash = hashDecision(decision);
        int inserted = jdbc.sql("""
                        INSERT INTO processed_events (event_id, application_id, decision_hash, processed_at)
                        SELECT id, aggregate_id, :decisionHash, :processedAt
                        FROM outbox_events
                        WHERE id = :eventId AND aggregate_id = :applicationId
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", decision.eventId())
                .param("applicationId", decision.applicationId())
                .param("decisionHash", decisionHash)
                .param("processedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        if (inserted == 0) {
            ProcessedEvent existing = jdbc.sql("""
                            SELECT application_id, decision_hash
                            FROM processed_events
                            WHERE event_id = :eventId
                            """)
                    .param("eventId", decision.eventId())
                    .query((rs, rowNum) -> new ProcessedEvent(
                            rs.getObject("application_id", UUID.class),
                            rs.getString("decision_hash")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Decision event does not belong to this application"));
            if (!existing.applicationId().equals(decision.applicationId())
                    || !existing.decisionHash().equals(decisionHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Event ID was already used for a different decision");
            }
            return get(decision.applicationId());
        }

        Instant now = Instant.now();
        int updated = jdbc.sql("""
                        UPDATE applications
                        SET status = :status,
                            offer_apr = :offerApr,
                            offer_term_months = :offerTermMonths,
                            decline_reason = :declineReason,
                            notification_status = 'SIMULATED',
                            updated_at = :updatedAt
                        WHERE id = :applicationId AND status = 'SUBMITTED'
                        """)
                .param("status", decision.decision().equals("APPROVED") ? "OFFERED" : "DECLINED")
                .param("offerApr", decision.annualPercentageRate())
                .param("offerTermMonths", decision.termMonths())
                .param("declineReason", decision.reason())
                .param("updatedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("applicationId", decision.applicationId())
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Application is missing or already has a final decision");
        }

        appendAudit(
                decision.applicationId(),
                decision.eventId(),
                "ASYNC_REVIEW_COMPLETED",
                Map.of("decision", decision.decision()),
                now);
        if (decision.decision().equals("APPROVED")) {
            appendAudit(
                    decision.applicationId(),
                    decision.eventId(),
                    "OFFER_GENERATED",
                    Map.of(
                            "annualPercentageRate", decision.annualPercentageRate(),
                            "termMonths", decision.termMonths()),
                    now);
        } else {
            appendAudit(
                    decision.applicationId(),
                    decision.eventId(),
                    "APPLICATION_DECLINED",
                    Map.of("reason", decision.reason()),
                    now);
        }
        appendAudit(
                decision.applicationId(),
                decision.eventId(),
                "APPLICANT_NOTIFIED",
                Map.of("channel", "SIMULATED", "deliveryStatus", "RECORDED"),
                now);
        return get(decision.applicationId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ApplicationView get(UUID applicationId) {
        StoredView stored = jdbc.sql("""
                        SELECT id, applicant_reference, credit_score, annual_income, requested_amount,
                               status, offer_apr, offer_term_months, decline_reason, notification_status,
                               created_at, updated_at
                        FROM applications
                        WHERE id = :id
                        """)
                .param("id", applicationId)
                .query((rs, rowNum) -> new StoredView(
                        rs.getObject("id", UUID.class),
                        rs.getString("applicant_reference"),
                        rs.getInt("credit_score"),
                        rs.getBigDecimal("annual_income"),
                        rs.getBigDecimal("requested_amount"),
                        rs.getString("status"),
                        rs.getBigDecimal("offer_apr"),
                        rs.getObject("offer_term_months", Integer.class),
                        rs.getString("decline_reason"),
                        rs.getString("notification_status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found"));
        List<AuditView> audit = jdbc.sql("""
                        SELECT type, details::text, trace_id, created_at
                        FROM audit_records
                        WHERE application_id = :applicationId
                        ORDER BY id
                        """)
                .param("applicationId", applicationId)
                .query((rs, rowNum) -> new AuditView(
                        rs.getString("type"),
                        readJsonObject(rs.getString("details")),
                        rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        OfferView offer = stored.offerApr() == null
                ? null
                : new OfferView(stored.offerApr(), stored.offerTermMonths());
        return new ApplicationView(
                stored.id(),
                stored.applicantReference(),
                stored.creditScore(),
                stored.annualIncome(),
                stored.requestedAmount(),
                stored.status(),
                offer,
                stored.declineReason(),
                stored.notificationStatus(),
                stored.createdAt(),
                stored.updatedAt(),
                audit);
    }

    private StoredApplication findByIdempotencyKey(String key) {
        return jdbc.sql("""
                        SELECT id, request_hash, status, created_at
                        FROM applications
                        WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new StoredApplication(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    private String currentTraceId() {
        return tracer.currentSpan() == null ? "" : tracer.currentSpan().context().traceId();
    }

    private void validateDecision(DecisionController.DecisionRequest decision) {
        boolean approved = decision.decision().equals("APPROVED");
        if (approved && (decision.annualPercentageRate() == null || decision.termMonths() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approved decisions require offer terms");
        }
        if (approved && decision.reason() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approved decisions cannot include a decline reason");
        }
        if (!approved && (decision.reason() == null || decision.reason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Declined decisions require a reason");
        }
        if (!approved && (decision.annualPercentageRate() != null || decision.termMonths() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Declined decisions cannot include offer terms");
        }
    }

    private String hashDecision(DecisionController.DecisionRequest decision) {
        String canonical = String.join("|",
                decision.applicationId().toString(),
                decision.decision(),
                decision.annualPercentageRate() == null
                        ? ""
                        : decision.annualPercentageRate().stripTrailingZeros().toPlainString(),
                decision.termMonths() == null ? "" : decision.termMonths().toString(),
                decision.reason() == null ? "" : decision.reason());
        return sha256(canonical);
    }

    private void appendAudit(
            UUID applicationId, UUID eventId, String type, Map<String, Object> details, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO audit_records (application_id, event_id, type, details, trace_id, created_at)
                        VALUES (:applicationId, :eventId, :type, CAST(:details AS jsonb), :traceId, :createdAt)
                        """)
                .param("applicationId", applicationId)
                .param("eventId", eventId)
                .param("type", type)
                .param("details", json(details))
                .param("traceId", currentTraceId())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    private String hash(ApplicationController.SubmissionRequest request) {
        String canonical = String.join("|",
                request.applicantReference(),
                Integer.toString(request.creditScore()),
                request.annualIncome().stripTrailingZeros().toPlainString(),
                request.requestedAmount().stripTrailingZeros().toPlainString());
        return sha256(canonical);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize outbox event", exception);
        }
    }

    private Map<String, Object> readJsonObject(String value) {
        try {
            return objectMapper.readValue(value, new tools.jackson.core.type.TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to read audit details", exception);
        }
    }

    record Submission(UUID id, String status, boolean replayed, Instant createdAt) {}

    private record StoredApplication(UUID id, String requestHash, String status, Instant createdAt) {}

    private record ApplicationSubmittedEvent(
            UUID eventId,
            UUID applicationId,
            String eventType,
            int schemaVersion,
            String applicantReference,
            int creditScore,
            BigDecimal annualIncome,
            BigDecimal requestedAmount,
            Instant submittedAt) {}

    record ApplicationView(
            UUID id,
            String applicantReference,
            int creditScore,
            BigDecimal annualIncome,
            BigDecimal requestedAmount,
            String status,
            OfferView offer,
            String declineReason,
            String notificationStatus,
            Instant createdAt,
            Instant updatedAt,
            List<AuditView> audit) {}

    record OfferView(BigDecimal annualPercentageRate, int termMonths) {}

    record AuditView(String type, Map<String, Object> details, String traceId, Instant createdAt) {}

    private record ProcessedEvent(UUID applicationId, String decisionHash) {}

    private record StoredView(
            UUID id,
            String applicantReference,
            int creditScore,
            BigDecimal annualIncome,
            BigDecimal requestedAmount,
            String status,
            BigDecimal offerApr,
            Integer offerTermMonths,
            String declineReason,
            String notificationStatus,
            Instant createdAt,
            Instant updatedAt) {}
}
