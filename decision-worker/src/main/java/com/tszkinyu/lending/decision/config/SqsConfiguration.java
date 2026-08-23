package com.tszkinyu.lending.decision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration(proxyBeanMethods = false)
class SqsConfiguration {

    @Bean
    SqsClient sqsClient(LendingProperties properties) {
        LendingProperties.Aws aws = properties.aws();
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(aws.region()));
        if (aws.endpoint() != null) {
            builder.endpointOverride(aws.endpoint());
        }
        if (aws.accessKey() != null && !aws.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(aws.accessKey(), aws.secretKey())));
        }
        return builder.build();
    }
}
