package com.tszkinyu.lending.application.outbox;

import java.util.HashMap;
import java.util.Map;

import com.tszkinyu.lending.application.config.LendingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

@Component
class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final SqsClient sqs;
    private final LendingProperties properties;
    private final Counter published;
    private final Counter failures;

    OutboxPublisher(
            OutboxRepository repository,
            SqsClient sqs,
            LendingProperties properties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.sqs = sqs;
        this.properties = properties;
        this.published = meterRegistry.counter("lending.outbox.published");
        this.failures = meterRegistry.counter("lending.outbox.publish.failures");
        Gauge.builder("lending.outbox.pending", repository, OutboxRepository::pendingCount)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${lending.outbox.fixed-delay:500ms}")
    void publishNext() {
        repository.claimNext().ifPresent(this::send);
    }

    private void send(OutboxRepository.ClaimedEvent event) {
        try {
            Map<String, MessageAttributeValue> attributes = new HashMap<>();
            attributes.put("eventType", stringAttribute(event.eventType()));
            if (event.traceparent() != null && !event.traceparent().isBlank()) {
                attributes.put("traceparent", stringAttribute(event.traceparent()));
            }
            sqs.sendMessage(request -> request
                    .queueUrl(properties.sqs().applicationEventsUrl())
                    .messageBody(event.payload())
                    .messageAttributes(attributes));
            repository.markPublished(event);
            published.increment();
        } catch (RuntimeException failure) {
            failures.increment();
            repository.releaseAfterFailure(event, failure);
            LOGGER.warn("Outbox publish failed for event {}; it remains pending", event.id(), failure);
        }
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
