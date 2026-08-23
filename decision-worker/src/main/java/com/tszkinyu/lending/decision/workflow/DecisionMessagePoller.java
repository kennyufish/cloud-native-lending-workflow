package com.tszkinyu.lending.decision.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tszkinyu.lending.decision.config.LendingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class DecisionMessagePoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionMessagePoller.class);

    private final SqsClient sqs;
    private final LendingProperties properties;
    private final ObjectMapper objectMapper;
    private final DecisionPolicy policy;
    private final DecisionCallbackClient callback;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter processed;
    private final Counter failures;
    private final Timer latency;

    DecisionMessagePoller(
            SqsClient sqs,
            LendingProperties properties,
            ObjectMapper objectMapper,
            DecisionPolicy policy,
            DecisionCallbackClient callback,
            Tracer tracer,
            Propagator propagator,
            MeterRegistry meterRegistry) {
        this.sqs = sqs;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.callback = callback;
        this.tracer = tracer;
        this.propagator = propagator;
        this.processed = meterRegistry.counter("lending.decision.processed");
        this.failures = meterRegistry.counter("lending.decision.failures");
        this.latency = meterRegistry.timer("lending.decision.latency");
    }

    @Scheduled(fixedDelayString = "${lending.poller.fixed-delay:500ms}")
    void poll() {
        List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(properties.sqs().applicationEventsUrl())
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(properties.poller().waitTimeSeconds())
                        .messageAttributeNames("All")
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                .messages();
        messages.forEach(this::process);
    }

    private void process(Message message) {
        Map<String, String> carrier = new HashMap<>();
        message.messageAttributes().forEach((key, value) -> carrier.put(key, value.stringValue()));
        Span span = propagator.extract(carrier, Map::get)
                .name("process application submitted")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "aws_sqs")
                .tag("messaging.message.id", message.messageId())
                .start();
        Timer.Sample sample = Timer.start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            ApplicationSubmittedEvent event = readEvent(message.body());
            if (!event.eventType().equals("APPLICATION_SUBMITTED") || event.schemaVersion() != 1) {
                throw new IllegalArgumentException("Unsupported event type or schema version");
            }
            DecisionPolicy.Decision decision = policy.evaluate(event);
            callback.record(event.eventId(), event.applicationId(), decision);
            sqs.deleteMessage(request -> request
                    .queueUrl(properties.sqs().applicationEventsUrl())
                    .receiptHandle(message.receiptHandle()));
            processed.increment();
        } catch (RuntimeException failure) {
            failures.increment();
            span.error(failure);
            String receiveCount = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
            LOGGER.warn(
                    "Decision event {} failed on receive {}; SQS will retry or redrive it",
                    message.messageId(),
                    receiveCount,
                    failure);
        } finally {
            sample.stop(latency);
            span.end();
        }
    }

    private ApplicationSubmittedEvent readEvent(String body) {
        try {
            return objectMapper.readValue(body, ApplicationSubmittedEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid application event", exception);
        }
    }
}
