package com.recomate.session.repository;

import com.recomate.session.domain.RecommendationSession;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Repository
public class DynamoSessionRepository implements SessionRepository {
    private final DynamoDbTable<RecommendationSession> table;

    public DynamoSessionRepository(DynamoDbTable<RecommendationSession> table) {
        this.table = table;
    }

    @Override
    public void save(RecommendationSession session) {
        table.putItem(session);
    }
}
