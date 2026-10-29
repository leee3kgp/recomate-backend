package com.recomate.user.infrastructure;

import com.recomate.user.SessionDataEraser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Component
public class DynamoDbSessionDataEraser implements SessionDataEraser {
    private final DynamoDbClient db;
    private final String table;
    public DynamoDbSessionDataEraser(DynamoDbClient db, @Value("${recomate.dynamodb.sessions-table}") String table) {
        this.db = db;
        this.table = table;
    }

    @Override
    public void deleteAll(String userId) {
        Map<String, AttributeValue> cursor = Map.of();
        do {
            // The BE2 table is keyed only by sessionId. Its user GSI is eventually consistent,
            // so it cannot prove that every pre-withdrawal session has been deleted.
            // Once DELETING is persisted, transactional writers cannot add/update own sessions.
            var scan = ScanRequest.builder().tableName(table).consistentRead(true).limit(100)
                    .filterExpression("userId = :userId")
                    .expressionAttributeValues(Map.of(":userId", AttributeValue.builder().s(userId).build()))
                    .projectionExpression("userId, sessionId");
            if (!cursor.isEmpty()) scan.exclusiveStartKey(cursor);
            var page = db.scan(scan.build());
            List<WriteRequest> deletes = new ArrayList<>();
            for (Map<String, AttributeValue> item : page.items()) {
                if (!userId.equals(item.get("userId").s()) || item.get("sessionId") == null) {
                    throw new IllegalStateException("Unexpected session owner or schema");
                }
                deletes.add(WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(Map.of(
                        "sessionId", item.get("sessionId"))).build()).build());
                if (deletes.size() == 25) { eraseBatch(deletes); deletes.clear(); }
            }
            if (!deletes.isEmpty()) eraseBatch(deletes);
            cursor = page.lastEvaluatedKey();
        } while (!cursor.isEmpty());
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
        throw new IllegalStateException("Session deletion has unprocessed items; retry required");
    }
}
