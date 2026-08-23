package com.tszkinyu.lending.application.outbox;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    @Transactional
    Optional<ClaimedEvent> claimNext() {
        Optional<StoredEvent> candidate = jdbc.sql("""
                        SELECT id, event_type, payload::text, traceparent
                        FROM outbox_events
                        WHERE published_at IS NULL
                          AND (locked_until IS NULL OR locked_until < now())
                        ORDER BY created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """)
                .query((rs, rowNum) -> new StoredEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("traceparent")))
                .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        StoredEvent event = candidate.get();
        jdbc.sql("""
                        UPDATE outbox_events
                        SET lock_token = :token, locked_until = now() + interval '30 seconds',
                            publish_attempts = publish_attempts + 1
                        WHERE id = :id
                        """)
                .param("token", token)
                .param("id", event.id())
                .update();
        return Optional.of(new ClaimedEvent(
                event.id(), event.eventType(), event.payload(), event.traceparent(), token));
    }

    @Transactional
    void markPublished(ClaimedEvent event) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET published_at = :publishedAt, lock_token = NULL, locked_until = NULL, last_error = NULL
                        WHERE id = :id AND lock_token = :token
                        """)
                .param("publishedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("id", event.id())
                .param("token", event.lockToken())
                .update();
    }

    @Transactional
    void releaseAfterFailure(ClaimedEvent event, RuntimeException failure) {
        String message = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        jdbc.sql("""
                        UPDATE outbox_events
                        SET locked_until = now() + interval '5 seconds', last_error = :error
                        WHERE id = :id AND lock_token = :token
                        """)
                .param("error", message.substring(0, Math.min(message.length(), 500)))
                .param("id", event.id())
                .param("token", event.lockToken())
                .update();
    }

    record ClaimedEvent(UUID id, String eventType, String payload, String traceparent, UUID lockToken) {}

    private record StoredEvent(UUID id, String eventType, String payload, String traceparent) {}
}
