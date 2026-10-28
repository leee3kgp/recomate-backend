package com.recomate.config;

import com.recomate.session.domain.RecommendationSession;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration
public class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${aws.region}") String region,
                                  @Value("${aws.dynamodb.endpoint:}") String endpoint) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)); // 로컬 DynamoDB용
        }
        return builder.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    DynamoDbTable<RecommendationSession> sessionTable(DynamoDbEnhancedClient enhancedClient,
            @Value("${recomate.dynamodb.session-table}") String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(RecommendationSession.class));
    }
}
