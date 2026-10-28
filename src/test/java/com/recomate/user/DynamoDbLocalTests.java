package com.recomate.user;

import com.recomate.common.ApiException;
import com.recomate.user.infrastructure.DynamoDbSessionDataEraser;
import com.recomate.user.infrastructure.DynamoDbUserRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real DynamoDB protocol tests; dummy credentials and loopback endpoint only. */
@EnabledIfEnvironmentVariable(named = "DYNAMODB_LOCAL_ENDPOINT", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbLocalTests {
    private DynamoDbClient db;
    private DynamoDbUserRepository users;
    private DynamoDbSessionDataEraser sessions;
    private String usersTable;
    private String sessionsTable;

    @BeforeAll
    void createIsolatedLocalTables() {
        URI endpoint = URI.create(System.getenv("DYNAMODB_LOCAL_ENDPOINT"));
        if (!List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())) {
            throw new IllegalArgumentException("Tests require a loopback DynamoDB Local endpoint");
        }
        db = DynamoDbClient.builder().region(Region.AP_SOUTHEAST_2).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("localTest", "localTestSecret"))).build();
        String suffix = UUID.randomUUID().toString();
        usersTable = "test-users-" + suffix;
        sessionsTable = "test-sessions-" + suffix;
        db.createTable(CreateTableRequest.builder().tableName(usersTable).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build()).build());
        db.createTable(CreateTableRequest.builder().tableName(sessionsTable).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sessionId").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sessionId").attributeType(ScalarAttributeType.S).build()).build());
        users = new DynamoDbUserRepository(db, usersTable);
        sessions = new DynamoDbSessionDataEraser(db, sessionsTable);
    }

    @AfterAll
    void cleanupOnlyTheseTestTables() {
        if (db != null) {
            try {
                if (usersTable != null) db.deleteTable(DeleteTableRequest.builder().tableName(usersTable).build());
                if (sessionsTable != null) db.deleteTable(DeleteTableRequest.builder().tableName(sessionsTable).build());
            } finally { db.close(); }
        }
    }

    private UserRecord seed(String id, String name) {
        return new UserRecord(id, "test@example.test", name, List.of(), UserRecord.Status.ACTIVE, null);
    }

    @Test
    void concurrentFirstRequestsCreateExactlyOneProfile() throws Exception {
        String id = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> users.createIfAbsent(seed(id, "name-" + index)))).toList();
            String winner = tasks.getFirst().get().nickname();
            for (var task : tasks) assertThat(task.get().nickname()).isEqualTo(winner);
            assertThat(users.find(id).orElseThrow().nickname()).isEqualTo(winner);
        }
    }

    @Test
    void independentConcurrentUpdatesDoNotOverwriteEachOther() throws Exception {
        String id = UUID.randomUUID().toString();
        users.createIfAbsent(seed(id, "before"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var name = executor.submit(() -> users.updateNickname(id, "after"));
            var genres = executor.submit(() -> users.replaceGenres(id, List.of("ACTION", "SF")));
            name.get(); genres.get();
        }
        var stored = users.find(id).orElseThrow();
        assertThat(stored.nickname()).isEqualTo("after");
        assertThat(stored.genres()).containsExactly("ACTION", "SF");
        assertThat(users.replaceGenres(id, List.of()).genres()).isEmpty();
    }

    @Test
    void nicknameReentryCreatesValidProfileAndUnblocksGenresWithoutChangingProviderData() {
        String id = UUID.randomUUID().toString();
        var directory = mock(IdentityDirectory.class);
        when(directory.profile(id)).thenReturn(new IdentityDirectory.ProfileSeed("test@example.test", null));
        var service = new UserService(users, directory, sessions, Clock.systemUTC());

        assertThatThrownBy(() -> service.profile(id)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("NICKNAME_REQUIRED");
        });
        assertThat(users.find(id)).isEmpty();
        assertThat(service.updateNickname(id, "  정우  ").nickname()).isEqualTo("정우");
        assertThat(service.profile(id).nickname()).isEqualTo("정우");
        assertThat(service.replaceGenres(id, List.of("ACTION"))).containsExactly("ACTION");
        assertThat(users.find(id).orElseThrow().genres()).containsExactly("ACTION");
        verify(directory, times(2)).profile(id);
        verifyNoMoreInteractions(directory);
    }

    @Test
    void equalDisplayNicknamesDoNotMergeUsersOrOverwriteAnotherProfile() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        users.createIfAbsent(seed(first, "same-name"));
        users.createIfAbsent(seed(second, "same-name"));
        users.updateNickname(first, "changed");
        assertThat(users.find(first).orElseThrow().nickname()).isEqualTo("changed");
        assertThat(users.find(second).orElseThrow().nickname()).isEqualTo("same-name");
        assertThat(users.find(second).orElseThrow().userId()).isEqualTo(second);
    }

    @Test
    void deletionRemovesPersonalFieldsBlocksUpsertsAndRetainsOnlyExpiringMarker() {
        String id = UUID.randomUUID().toString();
        users.createIfAbsent(seed(id, "private"));
        users.replaceGenres(id, List.of("DRAMA"));
        assertThat(users.beginDeletion(id).status()).isEqualTo(UserRecord.Status.DELETING);
        assertThat(users.beginDeletion(id).status()).isEqualTo(UserRecord.Status.DELETING);
        var pending = users.find(id).orElseThrow();
        assertThat(pending.email()).isNull();
        assertThat(pending.nickname()).isNull();
        assertThat(pending.genres()).isEmpty();
        assertThat(pending.expiresAt()).isNull();
        assertThatThrownBy(() -> users.updateNickname(id, "resurrect")).isInstanceOf(ApiException.class);
        assertThat(users.createIfAbsent(seed(id, "resurrect")).status()).isEqualTo(UserRecord.Status.DELETING);
        users.completeDeletion(id, 123L);
        users.completeDeletion(id, 456L);
        var item = db.getItem(GetItemRequest.builder().tableName(usersTable).key(DynamoDbUserRepository.key(id)).build()).item();
        assertThat(item.keySet()).containsExactlyInAnyOrder("userId", "status", "expiresAt");
        assertThat(item.get("expiresAt").n()).isEqualTo("123");
        assertThat(users.createIfAbsent(seed(id, "resurrect")).status()).isEqualTo(UserRecord.Status.DELETED);
    }

    @Test
    void nicknameAndGenreUpdatesCannotCreatePhantomProfiles() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> users.updateNickname(id, "x")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> users.replaceGenres(id, List.of())).isInstanceOf(ApiException.class);
        assertThat(users.find(id)).isEmpty();
        assertThat(users.beginDeletion(id).status()).isEqualTo(UserRecord.Status.DELETING);
    }

    @Test
    void paginatedSessionCleanupDeletesOnlyOwnersSessionsAndEmbeddedResults() {
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        for (int index = 0; index < 130; index++) putSession(owner, "session-" + index);
        putSession(other, "other-session");
        sessions.deleteAll(owner);
        assertThat(sessionCount(owner)).isZero();
        assertThat(sessionCount(other)).isEqualTo(1);
        sessions.deleteAll(owner);
    }

    @Test
    void cognitoFailureLeavesDurableStateAndRetryFinishesWithoutRecreatingData() {
        String id = UUID.randomUUID().toString();
        users.createIfAbsent(seed(id, "private"));
        putSession(id, "saved-result");
        var directory = mock(IdentityDirectory.class);
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(directory).delete(id);
        var now = Instant.parse("2026-10-27T00:00:00Z");
        var service = new UserService(users, directory, sessions, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalStateException.class);
        assertThat(users.find(id).orElseThrow().status()).isEqualTo(UserRecord.Status.DELETING);
        assertThat(users.find(id).orElseThrow().expiresAt()).isNull();
        assertThat(sessionCount(id)).isZero();
        assertThatThrownBy(() -> service.profile(id)).isInstanceOf(ApiException.class);
        service.delete(id);
        service.delete(id);
        var deleted = users.find(id).orElseThrow();
        assertThat(deleted.status()).isEqualTo(UserRecord.Status.DELETED);
        assertThat(deleted.expiresAt()).isEqualTo(now.plusSeconds(48 * 3600).getEpochSecond());
        verify(directory, times(2)).delete(id);
        verify(directory, never()).profile(id);
    }

    @Test
    void missingSessionTableIsFailureAndNeverSkipsCleanup() {
        String id = UUID.randomUUID().toString();
        var directory = mock(IdentityDirectory.class);
        var service = new UserService(users, directory,
                new DynamoDbSessionDataEraser(db, "missing-" + UUID.randomUUID()), Clock.systemUTC());
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(users.find(id).orElseThrow().status()).isEqualTo(UserRecord.Status.DELETING);
        verifyNoInteractions(directory);
    }

    private void putSession(String id, String sessionId) {
        db.putItem(PutItemRequest.builder().tableName(sessionsTable).item(Map.of(
                "userId", AttributeValue.builder().s(id).build(), "sessionId", AttributeValue.builder().s(sessionId).build(),
                "recommendations", AttributeValue.builder().l(AttributeValue.builder().m(Map.of("tmdbId", AttributeValue.builder().n("603").build())).build()).build())).build());
    }

    private int sessionCount(String id) {
        return db.query(QueryRequest.builder().tableName(sessionsTable).keyConditionExpression("userId = :id")
                .expressionAttributeValues(Map.of(":id", AttributeValue.builder().s(id).build())).select(Select.COUNT).build()).count();
    }
}
