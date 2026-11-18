package com.recomate.watched;

import com.recomate.common.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** PK userId, SK contentKey; LSI userId-watchedAt-index lists newest first with strongly consistent reads. */
@Repository
public class DynamoDbWatchedRepository implements WatchedRepository {
    public static final String INDEX = "userId-watchedAt-index";
    private final DynamoDbClient db;
    private final String table;
    private final String usersTable;

    public DynamoDbWatchedRepository(DynamoDbClient db, @Value("${recomate.dynamodb.watched-table}") String table,
            @Value("${recomate.dynamodb.users-table}") String usersTable) {
        this.db = db;
        this.table = table;
        this.usersTable = usersTable;
    }

    @Override
    public void save(String userId, WatchedItem item) {
        var activeUser = TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(usersTable)
                .key(Map.of("userId", text(userId)))
                .conditionExpression("attribute_exists(userId) AND #status = :active")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":active", text("ACTIVE"))).build()).build();
        try {
            db.transactWriteItems(TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(activeUser, put(table, userId, item)).build());
        } catch (TransactionCanceledException cancelled) {
            if (!cancelled.cancellationReasons().isEmpty()
                    && "ConditionalCheckFailed".equals(cancelled.cancellationReasons().get(0).code())) {
                throw ApiException.unauthorized();
            }
            throw cancelled;
        }
    }

    /** Shared with the session exclusion transaction so "이미 봤어요" and the watched entry commit together. */
    public static TransactWriteItem put(String table, String userId, WatchedItem item) {
        var values = new HashMap<String, AttributeValue>();
        values.put("userId", text(userId));
        values.put("contentKey", text(item.contentKey()));
        values.put("tmdbId", AttributeValue.builder().n(Long.toString(item.tmdbId())).build());
        values.put("mediaType", text(item.mediaType()));
        values.put("watchedAt", text(item.watchedAt()));
        if (item.title() != null) values.put("title", text(item.title()));
        if (item.posterPath() != null) values.put("posterPath", text(item.posterPath()));
        if (item.releaseYear() != null) {
            values.put("releaseYear", AttributeValue.builder().n(item.releaseYear().toString()).build());
        }
        return TransactWriteItem.builder().put(Put.builder().tableName(table).item(values).build()).build();
    }

    @Override
    public Page list(String userId, int limit, Cursor cursor) {
        var query = QueryRequest.builder().tableName(table).indexName(INDEX).consistentRead(true)
                .keyConditionExpression("userId = :owner")
                .expressionAttributeValues(Map.of(":owner", text(userId)))
                .scanIndexForward(false).limit(limit);
        if (cursor != null) {
            query.exclusiveStartKey(Map.of("userId", text(userId), "contentKey", text(cursor.contentKey()),
                    "watchedAt", text(cursor.watchedAt())));
        }
        var result = db.query(query.build());
        var last = result.lastEvaluatedKey();
        Cursor next = last.isEmpty() ? null : new Cursor(last.get("contentKey").s(), last.get("watchedAt").s());
        return new Page(result.items().stream().map(DynamoDbWatchedRepository::read).toList(), next);
    }

    @Override
    public void delete(String userId, String contentKey) {
        db.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(userId, contentKey)).build());
    }

    @Override
    public boolean contains(String userId, String contentKey) {
        // Query on the full primary key reuses the existing Query permission instead of adding GetItem.
        var result = db.query(QueryRequest.builder().tableName(table).consistentRead(true)
                .keyConditionExpression("userId = :owner AND contentKey = :key")
                .expressionAttributeValues(Map.of(":owner", text(userId), ":key", text(contentKey)))
                .projectionExpression("contentKey").build());
        return !result.items().isEmpty();
    }

    @Override
    public Set<String> keys(String userId) {
        Set<String> keys = new HashSet<>();
        Map<String, AttributeValue> cursor = Map.of();
        do {
            var query = QueryRequest.builder().tableName(table).consistentRead(true)
                    .keyConditionExpression("userId = :owner")
                    .expressionAttributeValues(Map.of(":owner", text(userId)))
                    .projectionExpression("contentKey");
            if (!cursor.isEmpty()) query.exclusiveStartKey(cursor);
            var page = db.query(query.build());
            page.items().forEach(item -> keys.add(item.get("contentKey").s()));
            cursor = page.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        return keys;
    }

    @Override
    public void deleteAll(String userId) {
        List<WriteRequest> deletes = new ArrayList<>();
        for (String contentKey : keys(userId)) {
            deletes.add(WriteRequest.builder().deleteRequest(DeleteRequest.builder()
                    .key(key(userId, contentKey)).build()).build());
            if (deletes.size() == 25) { eraseBatch(deletes); deletes.clear(); }
        }
        if (!deletes.isEmpty()) eraseBatch(deletes);
    }

    private void eraseBatch(List<WriteRequest> deletes) {
        Map<String, List<WriteRequest>> remaining = Map.of(table, List.copyOf(deletes));
        for (int attempt = 0; attempt < 5; attempt++) {
            remaining = db.batchWriteItem(BatchWriteItemRequest.builder().requestItems(remaining).build()).unprocessedItems();
            if (remaining.values().stream().allMatch(List::isEmpty)) return;
            if (attempt < 4) {
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(25L, 50L << attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Deletion interrupted", interrupted);
                }
            }
        }
        throw new IllegalStateException("Watched deletion has unprocessed items; retry required");
    }

    private static WatchedItem read(Map<String, AttributeValue> item) {
        return new WatchedItem(Long.parseLong(item.get("tmdbId").n()), item.get("mediaType").s(),
                item.containsKey("title") ? item.get("title").s() : null,
                item.containsKey("posterPath") ? item.get("posterPath").s() : null,
                item.containsKey("releaseYear") ? Integer.valueOf(item.get("releaseYear").n()) : null,
                item.get("watchedAt").s());
    }

    private static Map<String, AttributeValue> key(String userId, String contentKey) {
        return Map.of("userId", text(userId), "contentKey", text(contentKey));
    }

    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
}
