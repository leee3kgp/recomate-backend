package com.recomate.user;

import com.recomate.common.ApiException;
import com.recomate.user.infrastructure.CognitoIdentityDirectory;
import com.recomate.user.infrastructure.DynamoDbSessionDataEraser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InfrastructureFailureTests {
    @Test
    void alreadyDeletedCognitoUserIsSuccessButOtherProviderFailuresAreNot() {
        var client = mock(CognitoIdentityProviderClient.class);
        var directory = new CognitoIdentityDirectory(client, "pool-id");
        when(client.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().build())
                .thenThrow(TooManyRequestsException.builder().build());
        directory.delete("owner");
        assertThatThrownBy(() -> directory.delete("owner")).isInstanceOf(TooManyRequestsException.class);
        var request = ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        verify(client, times(2)).adminDeleteUser(request.capture());
        assertThat(request.getValue().username()).isEqualTo("owner");
        assertThat(request.getValue().userPoolId()).isEqualTo("pool-id");
    }

    @Test
    void missingDisabledOrMismatchedCognitoUserCannotCreateProfile() {
        var client = mock(CognitoIdentityProviderClient.class);
        var directory = new CognitoIdentityDirectory(client, "pool-id");
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().build())
                .thenReturn(AdminGetUserResponse.builder().enabled(false).build())
                .thenReturn(AdminGetUserResponse.builder().enabled(true).userAttributes(
                        AttributeType.builder().name("sub").value("victim").build()).build());
        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> directory.profile("owner")).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void cognitoProfileProvidesOnlySignupSeed() {
        var client = mock(CognitoIdentityProviderClient.class);
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(AdminGetUserResponse.builder().enabled(true)
                .userAttributes(AttributeType.builder().name("sub").value("owner").build(),
                        AttributeType.builder().name("email").value("owner@example.test").build(),
                        AttributeType.builder().name("nickname").value("정우").build()).build());
        var seed = new CognitoIdentityDirectory(client, "pool-id").profile("owner");
        assertThat(seed.email()).isEqualTo("owner@example.test");
        assertThat(seed.nickname()).isEqualTo("정우");
    }

    @Test
    void unprocessedBatchItemsAreRetriedWithoutResendingCompletedItems() {
        var db = mock(DynamoDbClient.class);
        var first = session("owner", "first");
        var second = session("owner", "second");
        when(db.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(first, second)).build());
        WriteRequest remaining = WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(second).build()).build();
        when(db.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of("sessions", List.of(remaining))).build())
                .thenReturn(BatchWriteItemResponse.builder().build());
        new DynamoDbSessionDataEraser(db, "sessions").deleteAll("owner");
        var requests = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(db, times(2)).batchWriteItem(requests.capture());
        assertThat(requests.getAllValues().getFirst().requestItems().get("sessions")).hasSize(2);
        assertThat(requests.getAllValues().getLast().requestItems().get("sessions")).containsExactly(remaining);
    }

    @Test
    void exhaustedBatchRetriesFailInsteadOfLosingPendingDeletion() {
        var db = mock(DynamoDbClient.class);
        var item = session("owner", "session");
        WriteRequest pending = WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(item).build()).build();
        when(db.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(item)).build());
        when(db.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of("sessions", List.of(pending))).build());
        assertThatThrownBy(() -> new DynamoDbSessionDataEraser(db, "sessions").deleteAll("owner"))
                .isInstanceOf(IllegalStateException.class);
        verify(db, times(5)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void cleanupRefusesToDeleteAnUnexpectedOwner() {
        var db = mock(DynamoDbClient.class);
        when(db.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(session("victim", "session"))).build());
        assertThatThrownBy(() -> new DynamoDbSessionDataEraser(db, "sessions").deleteAll("owner"))
                .isInstanceOf(IllegalStateException.class);
        verify(db, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void completionWriteFailureCanBeRetriedAfterCognitoDeletion() {
        var users = mock(UserRepository.class);
        var identities = mock(IdentityDirectory.class);
        var sessions = mock(SessionDataEraser.class);
        when(users.beginDeletion("owner")).thenReturn(new UserRecord("owner", null, null, List.of(), UserRecord.Status.DELETING, null));
        doThrow(new IllegalStateException("temporary storage failure")).doNothing().when(users).completeDeletion(eq("owner"), anyLong());
        var service = new UserService(users, identities, sessions, Clock.systemUTC());
        assertThatThrownBy(() -> service.delete("owner")).isInstanceOf(IllegalStateException.class);
        service.delete("owner");
        verify(identities, times(2)).delete("owner");
        verify(users, times(2)).completeDeletion(eq("owner"), anyLong());
    }

    @Test
    void nicknameLengthUsesUnicodeCodePointsAndWhitespaceOnlyIsRejected() {
        assertThat(UserInput.nickname("😀".repeat(20))).isEqualTo("😀".repeat(20));
        assertThatThrownBy(() -> UserInput.nickname("😀".repeat(21))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UserInput.nickname("\u00a0\u3000")).isInstanceOf(ApiException.class);
        assertThat(UserInput.nickname("\u00a0정우\u3000")).isEqualTo("정우");
    }

    private static Map<String, AttributeValue> session(String owner, String sessionId) {
        return Map.of("userId", AttributeValue.builder().s(owner).build(), "sessionId", AttributeValue.builder().s(sessionId).build());
    }
}
