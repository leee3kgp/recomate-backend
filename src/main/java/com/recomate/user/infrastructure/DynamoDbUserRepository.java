package com.recomate.user.infrastructure;

import com.recomate.common.ApiException;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Repository
public class DynamoDbUserRepository implements UserRepository {
    private final DynamoDbClient db;
    private final String table;

    public DynamoDbUserRepository(DynamoDbClient db, @Value("${recomate.dynamodb.users-table}") String table) {
        this.db = db;
        this.table = table;
    }

    @Override
    public Optional<UserRecord> find(String userId) {
        Map<String, AttributeValue> item = db.getItem(GetItemRequest.builder().tableName(table)
                .key(key(userId)).consistentRead(true).build()).item();
        return item.isEmpty() ? Optional.empty() : Optional.of(read(item));
    }

    @Override
    public UserRecord createIfAbsent(UserRecord user) {
        Map<String, AttributeValue> item = Map.of("userId", text(user.userId()), "email", text(user.email()),
                "nickname", text(user.nickname()), "genres", list(user.genres()), "status", text("ACTIVE"));
        try {
            db.putItem(PutItemRequest.builder().tableName(table).item(item)
                    .conditionExpression("attribute_not_exists(userId)").build());
            return user;
        } catch (ConditionalCheckFailedException concurrentCreation) {
            return find(user.userId()).orElseThrow(() -> new IllegalStateException("Concurrent user disappeared"));
        }
    }

    @Override
    public UserRecord updateNickname(String userId, String nickname) {
        return updateActive(userId, "nickname", text(nickname));
    }

    @Override
    public UserRecord replaceGenres(String userId, List<String> genres) {
        return updateActive(userId, "genres", list(genres));
    }

    private UserRecord updateActive(String userId, String field, AttributeValue value) {
        try {
            return read(db.updateItem(UpdateItemRequest.builder().tableName(table).key(key(userId))
                    .updateExpression("SET #field = :value")
                    .conditionExpression("attribute_exists(userId) AND #status = :active")
                    .expressionAttributeNames(Map.of("#field", field, "#status", "status"))
                    .expressionAttributeValues(Map.of(":value", value, ":active", text("ACTIVE")))
                    .returnValues(ReturnValue.ALL_NEW).build()).attributes());
        } catch (ConditionalCheckFailedException deletedOrMissing) {
            throw ApiException.unauthorized();
        }
    }

    @Override
    public UserRecord beginDeletion(String userId) {
        try {
            // Intentional upsert: a user may withdraw before ever fetching/creating their app profile.
            return read(db.updateItem(UpdateItemRequest.builder().tableName(table).key(key(userId))
                    .updateExpression("SET #status = :deleting REMOVE email, nickname, genres, expiresAt")
                    .conditionExpression("attribute_not_exists(userId) OR #status = :active")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":deleting", text("DELETING"), ":active", text("ACTIVE")))
                    .returnValues(ReturnValue.ALL_NEW).build()).attributes());
        } catch (ConditionalCheckFailedException alreadyDeleting) {
            return find(userId).orElseThrow(() -> new IllegalStateException("Deletion state disappeared"));
        }
    }

    @Override
    public void completeDeletion(String userId, long expiresAt) {
        try {
            db.updateItem(UpdateItemRequest.builder().tableName(table).key(key(userId))
                    .updateExpression("SET #status = :deleted, expiresAt = :expiresAt")
                    .conditionExpression("#status = :deleting")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":deleted", text("DELETED"), ":deleting", text("DELETING"),
                            ":expiresAt", AttributeValue.builder().n(Long.toString(expiresAt)).build())).build());
        } catch (ConditionalCheckFailedException concurrentCompletion) {
            if (find(userId).map(user -> user.status() == UserRecord.Status.DELETED).orElse(false)) return;
            throw new IllegalStateException("Unexpected deletion state");
        }
    }

    public static Map<String, AttributeValue> key(String userId) { return Map.of("userId", text(userId)); }
    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue list(List<String> values) {
        return AttributeValue.builder().l(values.stream().map(DynamoDbUserRepository::text).toList()).build();
    }

    private static UserRecord read(Map<String, AttributeValue> item) {
        var status = UserRecord.Status.valueOf(item.get("status").s());
        String email = item.containsKey("email") ? item.get("email").s() : null;
        String nickname = item.containsKey("nickname") ? item.get("nickname").s() : null;
        List<String> genres = item.containsKey("genres") ? item.get("genres").l().stream().map(AttributeValue::s).toList() : List.of();
        Long expiresAt = item.containsKey("expiresAt") ? Long.valueOf(item.get("expiresAt").n()) : null;
        return new UserRecord(item.get("userId").s(), email, nickname, genres, status, expiresAt);
    }
}
