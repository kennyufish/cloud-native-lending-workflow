package com.tszkinyu.lending.application.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lending")
public record LendingProperties(
        Aws aws,
        Sqs sqs,
        String internalApiToken) {

    public record Aws(
            URI endpoint,
            String region,
            String accessKey,
            String secretKey) {}

    public record Sqs(String applicationEventsUrl) {}
}
