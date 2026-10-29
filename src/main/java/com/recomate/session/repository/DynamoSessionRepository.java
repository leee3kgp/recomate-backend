package com.recomate.session.repository;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** PR #9 sessionId schema; all writes share the same withdrawal gate. */
@Repository
public class DynamoSessionRepository implements SessionRepository, RecommendationRepository {
    private static final TableSchema<RecommendationSession> SCHEMA = TableSchema.fromBean(RecommendationSession.class);
    private final DynamoDbClient db;
    private final String sessionsTable;
    private final String usersTable;

    public DynamoSessionRepository(DynamoDbClient db,
            @Value("${recomate.dynamodb.sessions-table}") String sessionsTable,
            @Value("${recomate.dynamodb.users-table}") String usersTable) {
        this.db = db;
        this.sessionsTable = sessionsTable;
        this.usersTable = usersTable;
    }

    @Override
    public void save(RecommendationSession session) {
        var put = Put.builder().tableName(sessionsTable).item(SCHEMA.itemToMap(session, true))
                .conditionExpression("attribute_not_exists(sessionId)").build();
        try {
            db.transactWriteItems(TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(activeUser(session.getUserId()), TransactWriteItem.builder().put(put).build()).build());
        } catch (TransactionCanceledException cancelled) {
            requireActiveUser(cancelled);
            throw cancelled;
        }
    }

    @Override
    public void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations) {
        validateRecommendations(recommendations);
        var values = new RecommendationSession();
        values.setRecommendations(recommendations);
        var update = Update.builder().tableName(sessionsTable).key(key(sessionId))
                .updateExpression("SET #results = :results")
                .conditionExpression("attribute_exists(sessionId) AND #owner = :owner AND "
                        + "(attribute_not_exists(#results) OR size(#results) = :empty)")
                .expressionAttributeNames(Map.of("#results", "recommendations", "#owner", "userId"))
                .expressionAttributeValues(Map.of(":owner", text(userId), ":empty", AttributeValue.builder().n("0").build(),
                        ":results", SCHEMA.itemToMap(values, true).get("recommendations"))).build();
        try {
            db.transactWriteItems(TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(activeUser(userId), TransactWriteItem.builder().update(update).build()).build());
        } catch (TransactionCanceledException cancelled) {
            requireActiveUser(cancelled);
            if (conditionalFailure(cancelled, 1)) {
                var item = db.getItem(GetItemRequest.builder().tableName(sessionsTable)
                        .key(key(sessionId)).consistentRead(true).build()).item();
                if (!userId.equals(item.getOrDefault("userId", text("")).s())) throw ApiException.notFound();
                if (item.containsKey("recommendations") && !item.get("recommendations").l().isEmpty()) {
                    throw ApiException.recommendationExists();
                }
            }
            throw cancelled;
        }
    }

    private TransactWriteItem activeUser(String userId) {
        return TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(usersTable)
                .key(Map.of("userId", text(userId)))
                .conditionExpression("attribute_exists(userId) AND #status = :active")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":active", text("ACTIVE"))).build()).build();
    }

    private static void requireActiveUser(TransactionCanceledException error) {
        if (conditionalFailure(error, 0)) throw ApiException.unauthorized();
    }

    private static boolean conditionalFailure(TransactionCanceledException error, int index) {
        return error.cancellationReasons().size() > index
                && "ConditionalCheckFailed".equals(error.cancellationReasons().get(index).code());
    }

    private static void validateRecommendations(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.size() > 3) {
            throw ApiException.validation("추천 결과는 최대 3개여야 합니다.");
        }
        var seen = new HashSet<String>();
        int rank = 1;
        for (Recommendation item : recommendations) {
            if (item == null || item.getRank() == null || item.getRank() != rank++ || item.getTmdbId() == null
                    || item.getTmdbId() < 1 || item.getMediaType() == null
                    || !List.of("movie", "tv").contains(item.getMediaType())
                    || !seen.add(item.getMediaType() + ":" + item.getTmdbId())) {
                throw ApiException.validation("추천 결과의 순위·콘텐츠 ID·유형을 확인해주세요.");
            }
        }
    }

    private static Map<String, AttributeValue> key(String sessionId) { return Map.of("sessionId", text(sessionId)); }
    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
}
