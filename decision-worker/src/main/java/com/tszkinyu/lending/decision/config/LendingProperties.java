package com.tszkinyu.lending.decision.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lending")
public record LendingProperties(
        Aws aws,
        Sqs sqs,
        Callback callback,
        Poller poller) {

    public record Aws(URI endpoint, String region, String accessKey, String secretKey) {}

    public record Sqs(String applicationEventsUrl) {}

    public record Callback(
            URI baseUrl,
            String internalApiToken,
            Duration connectTimeout,
            Duration readTimeout) {}

    public record Poller(int waitTimeSeconds) {}
}
