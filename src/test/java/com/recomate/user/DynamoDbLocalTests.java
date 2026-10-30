package com.recomate.user;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.ParticipantRequest;
import com.recomate.session.repository.DynamoSessionRepository;
import com.recomate.session.service.SessionService;
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
import java.util.concurrent.CountDownLatch;
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
                .keySchema(KeySchemaElement.builder().attributeName("sessionId").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sessionId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("createdAt").attributeType(ScalarAttributeType.S).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("userId-createdAt-index")
                        .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build()).build());
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
    void queryUsesOwnerIndexNewestFirstAndCursorSurvivesDeletedBoundary() {
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        users.createIfAbsent(seed(owner, "owner")); users.createIfAbsent(seed(other, "other"));
        var ids = new java.util.ArrayList<String>();
        for (int index = 0; index < 3; index++) {
            var session = new RecommendationSession();
            session.setSessionId(UUID.randomUUID().toString()); session.setUserId(owner);
            session.setParticipantCount(2); session.setCreatedAt("2026-10-30T00:00:0" + index + "Z");
            repository.save(session); ids.add(session.getSessionId());
        }
        var foreign = new RecommendationSession(); foreign.setSessionId(UUID.randomUUID().toString());
        foreign.setUserId(other); foreign.setParticipantCount(2); foreign.setCreatedAt("2026-10-31T00:00:00Z");
        repository.save(foreign);
        var first = repository.findByUser(owner, 1, null);
        assertThat(first.sessions()).extracting(RecommendationSession::getSessionId).containsExactly(ids.get(2));
        assertThat(first.nextCursor()).isNotNull();
        db.deleteItem(DeleteItemRequest.builder().tableName(sessionsTable)
                .key(Map.of("sessionId", AttributeValue.builder().s(ids.get(2)).build())).build());
        var next = repository.findByUser(owner, 2, first.nextCursor());
        assertThat(next.sessions()).extracting(RecommendationSession::getSessionId).containsExactly(ids.get(1), ids.get(0));
        // DynamoDB may return a boundary key even when that page exhausts the results.
        if (next.nextCursor() != null) {
            var last = repository.findByUser(owner, 2, next.nextCursor());
            assertThat(last.sessions()).isEmpty(); assertThat(last.nextCursor()).isNull();
        }
        assertThat(repository.findByUser(UUID.randomUUID().toString(), 20, null).sessions()).isEmpty();
    }

    @Test
    void consistentDetailReadsLegacyAndEnrichedItemsWithoutChangingStorage() {
        String id = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("sessionId", AttributeValue.builder().s(id).build());
        item.put("userId", AttributeValue.builder().s(owner).build());
        item.put("participantCount", AttributeValue.builder().n("2").build());
        item.put("createdAt", AttributeValue.builder().s("2026-10-30T00:00:00Z").build());
        db.putItem(PutItemRequest.builder().tableName(sessionsTable).item(item).build());
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var legacy = repository.findById(id).orElseThrow();
        assertThat(legacy.getRecommendations()).isEmpty(); assertThat(legacy.getAnalysis()).isNull();
        item.put("recommendations", AttributeValue.builder().l(AttributeValue.builder().m(Map.of(
                "rank", AttributeValue.builder().n("1").build(), "tmdbId", AttributeValue.builder().n("603").build(),
                "mediaType", AttributeValue.builder().s("movie").build(), "releaseYear", AttributeValue.builder().n("1999").build(),
                "genres", AttributeValue.builder().l(AttributeValue.builder().s("SF").build()).build(),
                "matchedParticipants", AttributeValue.builder().l(AttributeValue.builder().n("1").build()).build())).build()).build());
        item.put("analysis", AttributeValue.builder().m(Map.of("genreScores", AttributeValue.builder().l(
                AttributeValue.builder().m(Map.of("genre", AttributeValue.builder().s("SF").build(),
                        "count", AttributeValue.builder().n("1").build())).build()).build())).build());
        item.put("futureMetadata", AttributeValue.builder().s("ignored on read").build());
        db.putItem(PutItemRequest.builder().tableName(sessionsTable).item(item).build());
        var detail = repository.findById(id).orElseThrow();
        assertThat(detail.getRecommendations().getFirst().getReleaseYear()).isEqualTo(1999);
        assertThat(detail.getRecommendations().getFirst().getGenres()).containsExactly("SF");
        assertThat(detail.getAnalysis().getGenreScores().getFirst().getCount()).isEqualTo(1);
        assertThat(repository.findById(UUID.randomUUID().toString())).isEmpty();
        assertThat(storedSession(id)).isEqualTo(item);
    }

    @Test
    void resultsAndAnalysisShareTheSessionItemAndLeaveWithIt() {
        String owner = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var created = session(owner);
        repository.save(created);
        var result = recommendation();
        result.setReleaseYear(1999); result.setGenres(List.of("SF")); result.setMatchedParticipants(List.of(1, 2));
        var score = new SessionAnalysis.GenreScore(); score.setGenre("SF"); score.setCount(2);
        var match = new SessionAnalysis.ParticipantMatch(); match.setIndex(1); match.setMatchRate(0.9);
        var analysis = new SessionAnalysis();
        analysis.setGenreScores(List.of(score)); analysis.setParticipantMatch(List.of(match));
        repository.saveRecommendations(owner, created.getSessionId(), List.of(result), analysis);
        var stored = repository.findById(created.getSessionId()).orElseThrow();
        assertThat(stored.getRecommendations().getFirst().getMatchedParticipants()).containsExactly(1, 2);
        assertThat(stored.getAnalysis().getGenreScores().getFirst().getCount()).isEqualTo(2);
        assertThat(stored.getAnalysis().getParticipantMatch().getFirst().getMatchRate()).isEqualTo(0.9);
        sessions.deleteAll(owner);
        assertThat(storedSession(created.getSessionId())).isEmpty();
    }

    @Test
    void analysisCannotBeSavedDuringWithdrawalOrOverExistingResults() {
        String owner = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var created = session(owner);
        repository.save(created);
        repository.saveRecommendations(owner, created.getSessionId(), List.of(recommendation()));
        assertThat(storedSession(created.getSessionId())).doesNotContainKey("analysis");
        assertThatThrownBy(() -> repository.saveRecommendations(owner, created.getSessionId(), List.of(recommendation()), new SessionAnalysis()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("RECOMMENDATION_EXISTS"));
        var pending = session(owner);
        repository.save(pending);
        users.beginDeletion(owner);
        assertThatThrownBy(() -> repository.saveRecommendations(owner, pending.getSessionId(), List.of(recommendation()), new SessionAnalysis()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
        assertThat(storedSession(pending.getSessionId())).doesNotContainKey("analysis");
    }

    @Test
    void onlyTheOwnerCanDeleteAndRepeatedDeletionFindsNothing() {
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var created = session(owner);
        repository.save(created);
        assertThat(repository.deleteOwned(other, created.getSessionId())).isFalse();
        assertThat(storedSession(created.getSessionId())).isNotEmpty();
        assertThat(repository.deleteOwned(owner, created.getSessionId())).isTrue();
        assertThat(storedSession(created.getSessionId())).isEmpty();
        assertThat(repository.deleteOwned(owner, created.getSessionId())).isFalse();
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

    @Test
    void actualSessionCreationAndRecommendationStorageAreCoveredByWithdrawal() {
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        users.createIfAbsent(seed(other, "other"));
        var directory = mock(IdentityDirectory.class);
        var userService = new UserService(users, directory, sessions, Clock.systemUTC());
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var service = new SessionService(repository, userService, Clock.systemUTC());
        var created = service.createSession(owner, new CreateSessionRequest(2,
                List.of(new ParticipantRequest(1, List.of("ACTION")), new ParticipantRequest(2, List.of("SF")))));
        assertThat(UUID.fromString(created.getSessionId()).toString()).isEqualTo(created.getSessionId());
        assertThat(Instant.parse(created.getCreatedAt())).isNotNull();
        assertThat(storedSession(created.getSessionId()).get("recommendations").l()).isEmpty();
        assertThat(storedSession(created.getSessionId()).get("participants").l()).hasSize(2);
        repository.saveRecommendations(owner, created.getSessionId(), List.of(recommendation()));
        assertThat(storedSession(created.getSessionId()).get("recommendations").l()).hasSize(1);
        putSession(other, UUID.randomUUID().toString());
        userService.delete(owner);
        assertThat(storedSession(created.getSessionId())).isEmpty();
        assertThat(sessionCount(other)).isEqualTo(1);
        verify(directory).delete(owner);
        assertThat(users.find(owner).orElseThrow().status()).isEqualTo(UserRecord.Status.DELETED);
    }

    @Test
    void transactionalWritesRejectWithdrawalAndCannotRecreateDeletedSessions() {
        String owner = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var existing = session(owner);
        repository.save(existing);
        users.beginDeletion(owner);
        assertThatThrownBy(() -> repository.save(session(owner))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(401));
        assertThatThrownBy(() -> repository.saveRecommendations(owner, existing.getSessionId(), List.of(recommendation())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
        sessions.deleteAll(owner);
        assertThatThrownBy(() -> repository.saveRecommendations(owner, existing.getSessionId(), List.of(recommendation())))
                .isInstanceOf(ApiException.class);
        assertThat(storedSession(existing.getSessionId())).isEmpty();
        assertThat(sessionCount(owner)).isZero();
    }

    @Test
    void resultWritesCannotTargetAnotherUsersSessionOrOverwriteExistingResults() {
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        users.createIfAbsent(seed(owner, "owner"));
        users.createIfAbsent(seed(other, "other"));
        var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
        var created = session(owner);
        repository.save(created);
        assertThatThrownBy(() -> repository.saveRecommendations(other, created.getSessionId(), List.of(recommendation())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
        assertThatThrownBy(() -> repository.saveRecommendations(owner, "missing-session", List.of(recommendation())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
        repository.saveRecommendations(owner, created.getSessionId(), List.of(recommendation()));
        assertThatThrownBy(() -> repository.saveRecommendations(owner, created.getSessionId(), List.of(recommendation())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("RECOMMENDATION_EXISTS"));
        assertThat(sessionCount(other)).isZero();
    }

    @Test
    void concurrentSessionCreationAndWithdrawalLeaveNoOwnerData() throws Exception {
        for (int run = 0; run < 3; run++) {
            String owner = UUID.randomUUID().toString();
            users.createIfAbsent(seed(owner, "owner"));
            var repository = new DynamoSessionRepository(db, sessionsTable, usersTable);
            var directory = mock(IdentityDirectory.class);
            var service = new UserService(users, directory, sessions, Clock.systemUTC());
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var creation = executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < 12; index++) {
                        try { repository.save(session(owner)); }
                        catch (ApiException withdrawn) { assertThat(withdrawn.status()).isEqualTo(401); }
                        catch (TransactionCanceledException conflict) {
                            assertThat(conflict.cancellationReasons()).anySatisfy(reason ->
                                    assertThat(reason.code()).isEqualTo("TransactionConflict"));
                        }
                    }
                    return null;
                });
                var deletion = executor.submit(() -> { start.await(); service.delete(owner); return null; });
                start.countDown();
                creation.get(); deletion.get();
            }
            assertThat(sessionCount(owner)).isZero();
            assertThat(users.find(owner).orElseThrow().status()).isEqualTo(UserRecord.Status.DELETED);
        }
    }

    private RecommendationSession session(String owner) {
        var value = new RecommendationSession();
        value.setSessionId(UUID.randomUUID().toString());
        value.setUserId(owner);
        value.setParticipantCount(2);
        value.setCreatedAt(Instant.now().toString());
        return value;
    }

    private Recommendation recommendation() {
        var value = new Recommendation();
        value.setRank(1); value.setTmdbId(603L); value.setMediaType("movie");
        return value;
    }

    private Map<String, AttributeValue> storedSession(String sessionId) {
        return db.getItem(GetItemRequest.builder().tableName(sessionsTable).consistentRead(true)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build())).build()).item();
    }

    private void putSession(String id, String sessionId) {
        db.putItem(PutItemRequest.builder().tableName(sessionsTable).item(Map.of(
                "userId", AttributeValue.builder().s(id).build(), "sessionId", AttributeValue.builder().s(sessionId).build(),
                "recommendations", AttributeValue.builder().l(AttributeValue.builder().m(Map.of("tmdbId", AttributeValue.builder().n("603").build())).build()).build())).build());
    }

    private int sessionCount(String id) {
        return db.scan(ScanRequest.builder().tableName(sessionsTable).consistentRead(true).filterExpression("userId = :id")
                .expressionAttributeValues(Map.of(":id", AttributeValue.builder().s(id).build())).select(Select.COUNT).build()).count();
    }
}
