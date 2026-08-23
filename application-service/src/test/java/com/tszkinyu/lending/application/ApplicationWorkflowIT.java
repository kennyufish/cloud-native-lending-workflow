package com.tszkinyu.lending.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = ApplicationServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationWorkflowIT {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.8.1"))
            .withServices("sqs");
    private static final SqsClient SQS;
    private static final String QUEUE_URL;

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        SQS = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        QUEUE_URL = SQS.createQueue(request -> request
                        .queueName("application-events")
                        .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1")))
                .queueUrl();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lending.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("lending.aws.region", LOCALSTACK::getRegion);
        registry.add("lending.aws.access-key", LOCALSTACK::getAccessKey);
        registry.add("lending.aws.secret-key", LOCALSTACK::getSecretKey);
        registry.add("lending.sqs.application-events-url", () -> QUEUE_URL);
        registry.add("lending.outbox.fixed-delay", () -> "100ms");
        registry.add("lending.internal-api-token", () -> "integration-test-token");
        registry.add("management.tracing.export.otlp.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void purgeQueue() {
        SQS.purgeQueue(request -> request.queueUrl(QUEUE_URL));
    }

    @Test
    void duplicateIdempotencyKeyReturnsOneApplicationAndOneEvent() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        HttpResponse<String> created = submit(key, "APPLICANT-100", "720", "85000", "18000");
        HttpResponse<String> replayed = submit(key, "APPLICANT-100", "720", "85000", "18000");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(json(created.body()).get("id")).isEqualTo(json(replayed.body()).get("id"));
        assertThat(json(replayed.body()).get("replayed")).isEqualTo(true);

        String applicationId = json(created.body()).get("id").toString();
        assertThat(jdbcClient.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :applicationId")
                        .param("applicationId", UUID.fromString(applicationId))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        Message message = awaitMessageForApplication(applicationId);
        assertThat(message.messageAttributes().get("traceparent").stringValue())
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void reusingKeyForDifferentRequestIsRejected() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        assertThat(submit(key, "APPLICANT-200", "700", "80000", "15000").statusCode())
                .isEqualTo(201);

        assertThat(submit(key, "APPLICANT-200", "700", "80000", "16000").statusCode())
                .isEqualTo(409);
    }

    @Test
    void duplicateDecisionCallbackCreatesOneOfferAndOneNotification() throws Exception {
        HttpResponse<String> created = submit(
                "idem-" + UUID.randomUUID(), "APPLICANT-300", "760", "120000", "25000");
        String applicationId = json(created.body()).get("id").toString();
        String eventId = json(awaitMessageForApplication(applicationId).body()).get("eventId").toString();
        String decision = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "decision": "APPROVED",
                  "annualPercentageRate": 6.49,
                  "termMonths": 36,
                  "reason": null
                }
                """.formatted(eventId, applicationId);

        assertThat(recordDecision(decision).statusCode()).isEqualTo(200);
        assertThat(recordDecision(decision).statusCode()).isEqualTo(200);

        Map<String, Object> application = json(get("/api/v1/applications/" + applicationId).body());
        assertThat(application.get("status")).isEqualTo("OFFERED");
        Map<String, Object> offer = castMap(application.get("offer"));
        assertThat(new BigDecimal(offer.get("annualPercentageRate").toString()))
                .isEqualByComparingTo("6.49");
        List<Map<String, Object>> audit = castList(application.get("audit"));
        assertThat(audit.stream().filter(row -> row.get("type").equals("OFFER_GENERATED"))).hasSize(1);
        assertThat(audit.stream().filter(row -> row.get("type").equals("APPLICANT_NOTIFIED"))).hasSize(1);
    }

    @Test
    void contradictoryDecisionIsRejectedWithoutChangingApplication() throws Exception {
        HttpResponse<String> created = submit(
                "idem-" + UUID.randomUUID(), "APPLICANT-350", "700", "90000", "15000");
        String applicationId = json(created.body()).get("id").toString();
        UUID eventId = UUID.fromString(
                json(awaitMessageForApplication(applicationId).body()).get("eventId").toString());
        String decision = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "decision": "DECLINED",
                  "annualPercentageRate": 12.99,
                  "termMonths": 36,
                  "reason": "OUTSIDE_DEMO_POLICY"
                }
                """.formatted(eventId, applicationId);

        assertThat(recordDecision(decision).statusCode()).isEqualTo(400);
        assertThat(json(get("/api/v1/applications/" + applicationId).body()).get("status"))
                .isEqualTo("SUBMITTED");
        assertThat(jdbcClient.sql("SELECT count(*) FROM processed_events WHERE event_id = :eventId")
                        .param("eventId", eventId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void decisionEventCannotBeAppliedToAnotherApplication() throws Exception {
        HttpResponse<String> first = submit(
                "idem-" + UUID.randomUUID(), "APPLICANT-360", "720", "95000", "17000");
        String firstApplicationId = json(first.body()).get("id").toString();
        String firstEventId = json(awaitMessageForApplication(firstApplicationId).body()).get("eventId").toString();

        HttpResponse<String> second = submit(
                "idem-" + UUID.randomUUID(), "APPLICANT-370", "740", "110000", "20000");
        String secondApplicationId = json(second.body()).get("id").toString();
        String mismatchedDecision = """
                {
                  "eventId": "%s",
                  "applicationId": "%s",
                  "decision": "APPROVED",
                  "annualPercentageRate": 6.49,
                  "termMonths": 36,
                  "reason": null
                }
                """.formatted(firstEventId, secondApplicationId);

        assertThat(recordDecision(mismatchedDecision).statusCode()).isEqualTo(409);
        assertThat(json(get("/api/v1/applications/" + firstApplicationId).body()).get("status"))
                .isEqualTo("SUBMITTED");
        assertThat(json(get("/api/v1/applications/" + secondApplicationId).body()).get("status"))
                .isEqualTo("SUBMITTED");
        assertThat(jdbcClient.sql("SELECT count(*) FROM processed_events WHERE event_id = :eventId")
                        .param("eventId", UUID.fromString(firstEventId))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private HttpResponse<String> submit(
            String key, String applicantReference, String score, String income, String amount) throws Exception {
        String body = """
                {
                  "applicantReference": "%s",
                  "creditScore": %s,
                  "annualIncome": %s,
                  "requestedAmount": %s
                }
                """.formatted(applicantReference, score, income, amount);
        return send("/api/v1/applications", "POST", body, Map.of("Idempotency-Key", key));
    }

    private HttpResponse<String> recordDecision(String body) throws Exception {
        return send(
                "/internal/v1/decisions",
                "POST",
                body,
                Map.of("X-Internal-Token", "integration-test-token"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(
            String path, String method, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Message awaitMessageForApplication(String applicationId) {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> SQS.receiveMessage(ReceiveMessageRequest.builder()
                                                .queueUrl(QUEUE_URL)
                                                .maxNumberOfMessages(10)
                                                .messageAttributeNames("All")
                                                .waitTimeSeconds(1)
                                                .build())
                                        .messages()
                                        .stream()
                                        .filter(message -> applicationId.equals(messageApplicationId(message)))
                                        .findFirst()
                                        .orElse(null),
                        Objects::nonNull);
    }

    private String messageApplicationId(Message message) {
        try {
            return objectMapper.readTree(message.body()).get("applicationId").stringValue();
        } catch (JacksonException exception) {
            throw new AssertionError("Queue contained an invalid application event", exception);
        }
    }

    private Map<String, Object> json(String value) throws Exception {
        return objectMapper.readValue(value, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

}
