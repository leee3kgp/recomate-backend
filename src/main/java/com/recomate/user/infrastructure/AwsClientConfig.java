package com.recomate.user.infrastructure;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
public class AwsClientConfig {
    private static ClientOverrideConfiguration timeouts() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(3)).build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${recomate.aws.region}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).overrideConfiguration(timeouts()).build();
    }

    @Bean
    CognitoIdentityProviderClient cognitoClient(@Value("${recomate.aws.region}") String region) {
        return CognitoIdentityProviderClient.builder().region(Region.of(region)).overrideConfiguration(timeouts()).build();
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
