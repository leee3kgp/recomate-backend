package com.recomate.session;

import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.repository.SessionRepository;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTests {
    private static final String OWNER = "owner-sub";
    private static final UserRecord USER = new UserRecord(OWNER, "test@example.test", "정우", List.of(), UserRecord.Status.ACTIVE, null);
    private static final String REQUEST = """
            {"participantCount":2,"participants":[{"index":2,"genres":["SF","SF","ACTION"]},{"index":1,"genres":["DRAMA"]}]}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser eraser;
    @MockitoBean SessionRepository sessions;

    @BeforeEach
    void defaults() { when(users.find(OWNER)).thenReturn(Optional.of(USER)); }

    @Test
    void verifiedSubjectCreatesSessionUsingTheBe2RequestAndResponseContract() throws Exception {
        mvc.perform(post("/api/sessions?userId=victim").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/sessions/")))
                .andExpect(jsonPath("$.userId").value(OWNER))
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.participants[0].index").value(1))
                .andExpect(jsonPath("$.participants[1].genres", org.hamcrest.Matchers.contains("SF", "ACTION")))
                .andExpect(jsonPath("$.recommendations").isEmpty());
        var stored = ArgumentCaptor.forClass(RecommendationSession.class);
        verify(sessions).save(stored.capture());
        assertThat(stored.getValue().getUserId()).isEqualTo(OWNER);
        assertThat(UUID.fromString(stored.getValue().getSessionId())).isNotNull();
        assertThat(Instant.parse(stored.getValue().getCreatedAt())).isNotNull();
        verifyNoInteractions(identities, eraser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"participantCount\":1,\"participants\":[]}",
            "{\"participantCount\":7,\"participants\":[]}",
            "{\"participantCount\":2,\"participants\":[null,{\"index\":2,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[]},{\"index\":2,\"genres\":[\"SF\"]}]}"})
    void malformedOrInvalidFieldsUseTheSharedErrorEnvelope(String body) throws Exception {
        mvc.perform(post("/api/sessions").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(sessions, identities);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"participantCount\":3,\"participants\":[{\"index\":1,\"genres\":[\"SF\"]},{\"index\":2,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[\"SF\"]},{\"index\":1,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[\"UNKNOWN\"]},{\"index\":2,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[null]},{\"index\":2,\"genres\":[\"SF\"]}]}"})
    void invalidSessionRulesCannotWrite(String body) throws Exception {
        mvc.perform(post("/api/sessions").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(sessions, identities);
    }

    @Test
    void invalidInitialNicknameRequiresReentryBeforeAnySessionWrite() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.empty());
        when(identities.profile(OWNER)).thenReturn(new IdentityDirectory.ProfileSeed(USER.email(), null));
        mvc.perform(post("/api/sessions").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NICKNAME_REQUIRED"));
        verifyNoInteractions(sessions);
        verify(users, never()).createIfAbsent(any());
    }

    @Test
    void missingAuthenticationIsRejectedBeforeStorageAccess() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(users, sessions, identities);
    }

    @ParameterizedTest
    @EnumSource(value = UserRecord.Status.class, names = {"DELETING", "DELETED"})
    void withdrawnUsersCannotCreateSessions(UserRecord.Status state) throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, null, null, List.of(), state, null)));
        mvc.perform(post("/api/sessions").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(sessions, identities);
    }

    @Test
    void storageFailureReturnsShared500WithoutPrivateDetails() throws Exception {
        doThrow(new IllegalStateException("private diagnostic")).when(sessions).save(any());
        mvc.perform(post("/api/sessions").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void browserCanPreflightSessionCreationWithoutAuthentication() throws Exception {
        mvc.perform(options("/api/sessions").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
        verifyNoInteractions(users, sessions, identities);
    }
}
