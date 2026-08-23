package com.tszkinyu.lending.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = DecisionWorkerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionWorkerIT {

    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.8.1"))
            .withServices("sqs");
    private static final SqsClient SQS;
    private static final String QUEUE_URL;
    private static final String DLQ_URL;
    private static final HttpServer CALLBACK_SERVER;
    private static final AtomicReference<CapturedRequest> CAPTURED = new AtomicReference<>();
    private static final Set<UUID> FAIL_APPLICATIONS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> STALL_APPLICATIONS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger CALLBACK_ATTEMPTS = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        LOCALSTACK.start();
        SQS = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        DLQ_URL = SQS.createQueue(request -> request.queueName("decision-events-dlq")).queueUrl();
        String dlqArn = SQS.getQueueAttributes(request -> request
                        .queueUrl(DLQ_URL)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
        QUEUE_URL = SQS.createQueue(request -> request
                        .queueName("decision-events")
                        .attributes(Map.of(
                                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                                QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "1",
                                QueueAttributeName.REDRIVE_POLICY,
                                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}")))
                .queueUrl();
        try {
            CALLBACK_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        CALLBACK_SERVER.createContext("/internal/v1/decisions", DecisionWorkerIT::captureCallback);
        CALLBACK_SERVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        CALLBACK_SERVER.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("lending.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("lending.aws.region", LOCALSTACK::getRegion);
        registry.add("lending.aws.access-key", LOCALSTACK::getAccessKey);
        registry.add("lending.aws.secret-key", LOCALSTACK::getSecretKey);
        registry.add("lending.sqs.application-events-url", () -> QUEUE_URL);
        registry.add("lending.callback.base-url", () -> "http://127.0.0.1:" + CALLBACK_SERVER.getAddress().getPort());
        registry.add("lending.callback.internal-api-token", () -> "integration-test-token");
        registry.add("lending.callback.connect-timeout", () -> "200ms");
        registry.add("lending.callback.read-timeout", () -> "200ms");
        registry.add("lending.poller.fixed-delay", () -> "100ms");
        registry.add("lending.poller.wait-time-seconds", () -> "1");
        registry.add("management.tracing.export.otlp.enabled", () -> "false");
    }

    @BeforeEach
    void reset() {
        SQS.purgeQueue(request -> request.queueUrl(QUEUE_URL));
        SQS.purgeQueue(request -> request.queueUrl(DLQ_URL));
        CAPTURED.set(null);
        FAIL_APPLICATIONS.clear();
        STALL_APPLICATIONS.clear();
        CALLBACK_ATTEMPTS.set(0);
    }

    @Test
    void queueEventProducesOfferCallbackAndContinuesTrace() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String event = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "eventType": "APPLICATION_SUBMITTED",
                  "schemaVersion": 1,
                  "applicantReference": "APPLICANT-400",
                  "creditScore": 760,
                  "annualIncome": 120000,
                  "requestedAmount": 25000,
                  "submittedAt": "2026-08-23T12:00:00Z"
                }
                """.formatted(eventId, applicationId);
        SQS.sendMessage(request -> request
                .queueUrl(QUEUE_URL)
                .messageBody(event)
                .messageAttributes(Map.of(
                        "traceparent",
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("00-" + traceId + "-00f067aa0ba902b7-01")
                                .build())));

        CapturedRequest captured = Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(CAPTURED::get, value -> value != null);
        Map<String, Object> body = new ObjectMapper().readValue(captured.body(), new TypeReference<>() {});
        assertThat(body.get("eventId")).isEqualTo(eventId.toString());
        assertThat(body.get("applicationId")).isEqualTo(applicationId.toString());
        assertThat(body.get("decision")).isEqualTo("APPROVED");
        assertThat(body.get("annualPercentageRate").toString()).isEqualTo("6.49");
        assertThat(body.get("termMonths")).isEqualTo(36);
        assertThat(captured.traceparent()).contains(traceId);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<QueueAttributeName, String> attributes = SQS.getQueueAttributes(request -> request
                            .queueUrl(QUEUE_URL)
                            .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .attributes();
            assertThat(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).isEqualTo("0");
            assertThat(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).isEqualTo("0");
        });
    }

    @Test
    void failedCallbackIsRetriedAndRedrivenToDlq() {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        FAIL_APPLICATIONS.add(applicationId);
        String event = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "eventType": "APPLICATION_SUBMITTED",
                  "schemaVersion": 1,
                  "applicantReference": "APPLICANT-500",
                  "creditScore": 710,
                  "annualIncome": 90000,
                  "requestedAmount": 20000,
                  "submittedAt": "2026-08-23T12:00:00Z"
                }
                """.formatted(eventId, applicationId);
        SQS.sendMessage(request -> request.queueUrl(QUEUE_URL).messageBody(event));

        Message dlqMessage = Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .until(
                        () -> SQS.receiveMessage(request -> request
                                        .queueUrl(DLQ_URL)
                                        .maxNumberOfMessages(1)
                                        .waitTimeSeconds(1))
                                .messages(),
                        messages -> !messages.isEmpty())
                .getFirst();
        assertThat(CALLBACK_ATTEMPTS.get()).isGreaterThanOrEqualTo(2);
        assertThat(dlqMessage.body()).isEqualTo(event);
    }

    @Test
    void stalledCallbackTimesOutAndIsRedrivenToDlq() {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        STALL_APPLICATIONS.add(applicationId);
        String event = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "eventType": "APPLICATION_SUBMITTED",
                  "schemaVersion": 1,
                  "applicantReference": "APPLICANT-510",
                  "creditScore": 710,
                  "annualIncome": 90000,
                  "requestedAmount": 20000,
                  "submittedAt": "2026-08-23T12:00:00Z"
                }
                """.formatted(eventId, applicationId);
        SQS.sendMessage(request -> request.queueUrl(QUEUE_URL).messageBody(event));

        Message dlqMessage = Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .until(
                        () -> SQS.receiveMessage(request -> request
                                        .queueUrl(DLQ_URL)
                                        .maxNumberOfMessages(1)
                                        .waitTimeSeconds(1))
                                .messages(),
                        messages -> !messages.isEmpty())
                .getFirst();
        assertThat(CALLBACK_ATTEMPTS.get()).isGreaterThanOrEqualTo(2);
        assertThat(dlqMessage.body()).isEqualTo(event);
    }

    private static void captureCallback(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        CALLBACK_ATTEMPTS.incrementAndGet();
        Map<String, Object> decision = JSON.readValue(body, new TypeReference<>() {});
        UUID applicationId = UUID.fromString(decision.get("applicationId").toString());
        if (STALL_APPLICATIONS.contains(applicationId)) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        if (FAIL_APPLICATIONS.contains(applicationId)) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        CAPTURED.set(new CapturedRequest(body, exchange.getRequestHeaders().getFirst("traceparent")));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String body, String traceparent) {}
}
