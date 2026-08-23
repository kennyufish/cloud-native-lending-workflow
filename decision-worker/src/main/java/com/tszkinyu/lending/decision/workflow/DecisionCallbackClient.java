package com.tszkinyu.lending.decision.workflow;

import java.net.http.HttpClient;
import java.util.UUID;

import com.tszkinyu.lending.decision.config.LendingProperties;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Component
class DecisionCallbackClient {

    private final RestClient restClient;

    DecisionCallbackClient(RestClient.Builder builder, LendingProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.callback().connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.callback().readTimeout());
        this.restClient = builder
                .baseUrl(properties.callback().baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", properties.callback().internalApiToken())
                .build();
    }

    void record(
            UUID eventId,
            UUID applicationId,
            DecisionPolicy.Decision decision) {
        restClient.post()
                .uri("/internal/v1/decisions")
                .body(new DecisionRequest(
                        eventId,
                        applicationId,
                        decision.decision(),
                        decision.annualPercentageRate(),
                        decision.termMonths(),
                        decision.reason()))
                .retrieve()
                .toBodilessEntity();
    }

    private record DecisionRequest(
            UUID eventId,
            UUID applicationId,
            String decision,
            java.math.BigDecimal annualPercentageRate,
            Integer termMonths,
            String reason) {}
}
