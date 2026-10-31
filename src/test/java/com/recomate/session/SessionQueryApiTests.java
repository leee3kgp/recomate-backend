package com.recomate.session;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.SessionRepository;
import com.recomate.user.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SessionQueryApiTests {
    private static final String OWNER = "owner-sub", OTHER = "other-sub";
    private static final String ID = "01234567-89ab-4cde-8fab-0123456789ab";
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser eraser;
    @MockitoBean SessionRepository sessions;
    @MockitoBean RecommendationRepository results;

    @BeforeEach
    void defaults() {
        when(users.find(anyString())).thenReturn(Optional.empty());
        when(sessions.findById(anyString())).thenReturn(Optional.empty());
        when(sessions.findByUser(anyString(), anyInt(), any())).thenReturn(new SessionRepository.Page(List.of(), null));
    }

    private RecommendationSession session() {
        var value = new RecommendationSession();
        value.setSessionId(ID); value.setUserId(OWNER); value.setParticipantCount(2);
        value.setCreatedAt("2026-10-30T00:00:00Z");
        return value;
    }

    @Test
    void emptyListUsesJwtSubjectAndNeverCreatesAProfile() throws Exception {
        mvc.perform(get("/api/sessions").with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"sessions\":[],\"nextToken\":null}"));
        verify(sessions).findByUser(OWNER, 20, null);
        verify(users).find(OWNER);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(identities, eraser);
    }

    @Test
    void cursorContinuesForTheSameOwnerAndCannotBeReusedByAnotherOwner() throws Exception {
        var cursor = new SessionRepository.Cursor(ID, "2026-10-30T00:00:00Z");
        when(sessions.findByUser(OWNER, 1, null)).thenReturn(new SessionRepository.Page(List.of(session()), cursor));
        var response = mvc.perform(get("/api/sessions").queryParam("limit", "1").with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions[0].sessionId").value(ID))
                .andExpect(jsonPath("$.sessions[0].participantCount").value(2))
                .andExpect(jsonPath("$.sessions[0].recommendations").doesNotExist())
                .andExpect(jsonPath("$.sessions[0].userId").doesNotExist()).andReturn().getResponse();
        String token = JsonMapper.builder().build().readTree(response.getContentAsString()).get("nextToken").asText();
        mvc.perform(get("/api/sessions").queryParam("limit", "1").queryParam("nextToken", token)
                        .with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isOk());
        verify(sessions).findByUser(OWNER, 1, cursor);
        mvc.perform(get("/api/sessions").queryParam("nextToken", token).with(jwt().jwt(t -> t.subject(OTHER))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(sessions, never()).findByUser(eq(OTHER), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "1.5", "", "01", " 2", "abc", "999999999999999999"})
    void invalidLimitsNeverReachSessionStorage(String limit) throws Exception {
        mvc.perform(get("/api/sessions").queryParam("limit", limit).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsUnknownAndRepeatedParameters() throws Exception {
        for (String key : List.of("limit", "nextToken")) {
            mvc.perform(get("/api/sessions").queryParam(key, "1", "2").with(jwt()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/sessions").queryParam("userId", OTHER).with(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "!!!!", "AQ", "AAAA=", "AAAA+"})
    void rejectsMalformedTokens(String token) throws Exception {
        mvc.perform(get("/api/sessions").queryParam("nextToken", token).with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(sessions);
    }

    @Test
    void detailSupportsLegacyMissingResultsWithoutCallingCognito() throws Exception {
        var value = session(); value.setRecommendations(null);
        when(sessions.findById(ID)).thenReturn(Optional.of(value));
        mvc.perform(get("/api/sessions/" + ID).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionId").value(ID)).andExpect(jsonPath("$.recommendations").isEmpty())
                .andExpect(jsonPath("$.analysis").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(identities, eraser);
        verify(users, never()).createIfAbsent(any());
    }

    @Test
    void absentOptionalFieldsAreOmittedAndNullParticipantsBecomeEmpty() throws Exception {
        var value = session(); value.setParticipants(null);
        var result = new Recommendation(); result.setRank(1); result.setTmdbId(603L); result.setMediaType("movie");
        value.setRecommendations(List.of(result));
        when(sessions.findById(ID)).thenReturn(Optional.of(value));
        mvc.perform(get("/api/sessions/" + ID).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.participants").isEmpty())
                .andExpect(jsonPath("$.recommendations[0].tmdbId").value(603))
                .andExpect(jsonPath("$.recommendations[0].title").doesNotExist())
                .andExpect(jsonPath("$.recommendations[0].score").doesNotExist())
                .andExpect(jsonPath("$.recommendations[0].genres").doesNotExist());
    }

    @Test
    void largestAllowedLimitReachesStorage() throws Exception {
        mvc.perform(get("/api/sessions").queryParam("limit", "100").with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk());
        verify(sessions).findByUser(OWNER, 100, null);
    }

    @Test
    void detailReturnsStoredEnrichmentAndAnalysis() throws Exception {
        var value = session();
        var result = new Recommendation(); result.setRank(1); result.setTmdbId(603L); result.setMediaType("movie");
        result.setTitle("매트릭스"); result.setReleaseYear(1999); result.setVoteAverage(8.2);
        result.setGenres(List.of("ACTION", "SF")); result.setMatchedParticipants(List.of(1, 2));
        value.setRecommendations(List.of(result));
        var analysis = new SessionAnalysis(); var score = new SessionAnalysis.GenreScore();
        score.setGenre("SF"); score.setCount(2); analysis.setGenreScores(List.of(score)); value.setAnalysis(analysis);
        when(sessions.findById(ID)).thenReturn(Optional.of(value));
        mvc.perform(get("/api/sessions/" + ID.toUpperCase()).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations[0].title").value("매트릭스"))
                .andExpect(jsonPath("$.recommendations[0].releaseYear").value(1999))
                .andExpect(jsonPath("$.recommendations[0].genres[1]").value("SF"))
                .andExpect(jsonPath("$.recommendations[0].matchedParticipants[1]").value(2))
                .andExpect(jsonPath("$.analysis.genreScores[0].count").value(2));
    }

    @Test
    void absentAndForeignSessionsHaveTheSameResponse() throws Exception {
        String absent = mvc.perform(get("/api/sessions/" + ID).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var value = session(); value.setUserId(OTHER); when(sessions.findById(ID)).thenReturn(Optional.of(value));
        String foreign = mvc.perform(get("/api/sessions/" + ID).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreign).isEqualTo(absent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1-1-1-1-1", "0123456789ab4cde8fab0123456789ab"})
    void invalidUuidIsA400WithoutQueryingStorage(String id) throws Exception {
        mvc.perform(get("/api/sessions/" + id).with(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }

    @Test
    void detailRejectsQueryParameters() throws Exception {
        mvc.perform(get("/api/sessions/" + ID).queryParam("userId", OWNER).with(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }

    @Test
    void authenticationWithdrawalAndCorsApplyToBothRoutes() throws Exception {
        for (String path : List.of("/api/sessions", "/api/sessions/" + ID)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (var state : List.of(UserRecord.Status.DELETING, UserRecord.Status.DELETED)) {
                when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, null, null, List.of(), state, null)));
                mvc.perform(get(path).with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isUnauthorized());
            }
            mvc.perform(options(path).header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
        verifyNoInteractions(sessions, identities, eraser);
    }

    @Test
    void repositoryFailuresUseShared500WithoutLeakingDetails() throws Exception {
        when(sessions.findById(ID)).thenThrow(new IllegalStateException("private storage detail"));
        when(sessions.findByUser(eq(OWNER), anyInt(), any())).thenThrow(new IllegalStateException("private storage detail"));
        for (String path : List.of("/api/sessions", "/api/sessions/" + ID)) {
            mvc.perform(get(path).with(jwt().jwt(t -> t.subject(OWNER))))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        }
    }
}
