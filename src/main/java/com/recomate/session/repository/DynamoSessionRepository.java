package com.recomate.session.repository;

import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final int MAX_PARTICIPANTS = 6;
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
    public Optional<RecommendationSession> findById(String sessionId) {
        var item = db.getItem(GetItemRequest.builder().tableName(sessionsTable)
                .key(key(sessionId)).consistentRead(true).build()).item();
        return item.isEmpty() ? Optional.empty() : Optional.of(SCHEMA.mapToItem(item));
    }

    @Override
    public Page findByUser(String userId, int limit, Cursor cursor) {
        var query = QueryRequest.builder().tableName(sessionsTable).indexName(RecommendationSession.USER_INDEX)
                .keyConditionExpression("#owner = :owner")
                .expressionAttributeNames(Map.of("#owner", "userId", "#id", "sessionId", "#count", "participantCount", "#created", "createdAt"))
                .expressionAttributeValues(Map.of(":owner", text(userId)))
                .projectionExpression("#id, #count, #created").scanIndexForward(false).limit(limit);
        if (cursor != null) {
            query.exclusiveStartKey(Map.of("userId", text(userId), "sessionId", text(cursor.sessionId()),
                    "createdAt", text(cursor.createdAt())));
        }
        var result = db.query(query.build());
        var last = result.lastEvaluatedKey();
        Cursor next = last.isEmpty() ? null : new Cursor(last.get("sessionId").s(), last.get("createdAt").s());
        return new Page(result.items().stream().map(SCHEMA::mapToItem).toList(), next);
    }

    @Override
    public boolean deleteOwned(String userId, String sessionId) {
        // Deleting adds no data, so the shared access filter is enough; no ACTIVE transaction is needed.
        try {
            db.deleteItem(DeleteItemRequest.builder().tableName(sessionsTable).key(key(sessionId))
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(Map.of("#owner", "userId"))
                    .expressionAttributeValues(Map.of(":owner", text(userId))).build());
            return true;
        } catch (ConditionalCheckFailedException absentOrForeign) {
            return false;
        }
    }

    @Override
    public void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations,
            SessionAnalysis analysis) {
        validateRecommendations(recommendations);
        validateAnalysis(analysis);
        var values = new RecommendationSession();
        values.setRecommendations(recommendations);
        values.setAnalysis(analysis);
        var stored = SCHEMA.itemToMap(values, true);
        var names = new HashMap<>(Map.of("#results", "recommendations", "#owner", "userId"));
        var attributes = new HashMap<>(Map.of(":owner", text(userId), ":empty", AttributeValue.builder().n("0").build(),
                ":results", stored.get("recommendations")));
        String assignments = "SET #results = :results";
        if (analysis != null) {
            assignments += ", #analysis = :analysis";
            names.put("#analysis", "analysis");
            attributes.put(":analysis", stored.get("analysis"));
        }
        var update = Update.builder().tableName(sessionsTable).key(key(sessionId))
                .updateExpression(assignments)
                .conditionExpression("attribute_exists(sessionId) AND #owner = :owner AND "
                        + "(attribute_not_exists(#results) OR size(#results) = :empty)")
                .expressionAttributeNames(names).expressionAttributeValues(attributes).build();
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
            if (item.getGenres() != null && !item.getGenres().stream().allMatch(Genre::isValidCode)
                    || item.getMatchedParticipants() != null
                    && !item.getMatchedParticipants().stream().allMatch(DynamoSessionRepository::validParticipant)) {
                throw ApiException.validation("추천 결과의 장르 코드·참여자 번호를 확인해주세요.");
            }
        }
    }

    private static void validateAnalysis(SessionAnalysis analysis) {
        if (analysis == null) return;
        boolean validScores = analysis.getGenreScores().stream().allMatch(score -> score != null
                && Genre.isValidCode(score.getGenre()) && score.getCount() != null && score.getCount() >= 0);
        boolean validMatches = analysis.getParticipantMatch().stream().allMatch(match -> match != null
                && validParticipant(match.getIndex()) && match.getMatchRate() != null
                && match.getMatchRate() >= 0 && match.getMatchRate() <= 1);
        if (!validScores || !validMatches) throw ApiException.validation("취향 분석 값을 확인해주세요.");
    }

    private static boolean validParticipant(Integer index) {
        return index != null && index >= 1 && index <= MAX_PARTICIPANTS;
    }

    private static Map<String, AttributeValue> key(String sessionId) { return Map.of("sessionId", text(sessionId)); }
    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
}
