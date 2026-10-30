package com.recomate.session;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.SessionRepository;
import com.recomate.session.service.RecommendationGenerator;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class SessionCommandApiTests {
    private static final String OWNER = "owner-sub", OTHER = "other-sub";
    private static final String ID = "01234567-89ab-4cde-8fab-0123456789ab";
    private static final String RECOMMENDATIONS = "/api/sessions/" + ID + "/recommendations";
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser eraser;
    @MockitoBean SessionRepository sessions;
    @MockitoBean RecommendationRepository results;
    @MockitoBean RecommendationGenerator generator;

    @BeforeEach
    void defaults() {
        when(users.find(anyString())).thenReturn(Optional.empty());
        when(sessions.findById(anyString())).thenReturn(Optional.empty());
    }

    private RecommendationSession session() {
        var value = new RecommendationSession();
        value.setSessionId(ID); value.setUserId(OWNER); value.setParticipantCount(2);
        value.setCreatedAt("2026-10-30T00:00:00.000Z");
        return value;
    }

    private Recommendation recommendation() {
        var value = new Recommendation();
        value.setRank(1); value.setTmdbId(603L); value.setMediaType("movie"); value.setTitle("매트릭스");
        return value;
    }

    @Test
    void ownerDeletesSessionWithoutResponseBody() throws Exception {
        when(sessions.deleteOwned(OWNER, ID)).thenReturn(true);
        mvc.perform(delete("/api/sessions/" + ID.toUpperCase()).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(sessions).deleteOwned(OWNER, ID);
    }

    @Test
    void absentAndForeignDeletionUseTheSameNotFound() throws Exception {
        mvc.perform(delete("/api/sessions/" + ID).with(jwt().jwt(t -> t.subject(OTHER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void malformedDeletionIdNeverReachesStorage() throws Exception {
        mvc.perform(delete("/api/sessions/not-a-uuid").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @EnumSource(value = UserRecord.Status.class, names = {"DELETING", "DELETED"})
    void withdrawnUsersCannotDeleteOrGenerate(UserRecord.Status state) throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, null, null, List.of(), state, null)));
        for (var request : List.of(delete("/api/sessions/" + ID), post(RECOMMENDATIONS))) {
            mvc.perform(request.with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(sessions, results, generator);
    }

    @Test
    void bothRoutesRequireAuthenticationAndAllowPreflight() throws Exception {
        for (var request : List.of(delete("/api/sessions/" + ID), post(RECOMMENDATIONS))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        for (var entry : List.of(List.of("/api/sessions/" + ID, "DELETE"), List.of(RECOMMENDATIONS, "POST"))) {
            mvc.perform(options(entry.get(0)).header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", entry.get(1))
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

    @Test
    void generationStoresResultsAndAnalysisThenReturnsThem() throws Exception {
        var session = session();
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
        var score = new SessionAnalysis.GenreScore(); score.setGenre("SF"); score.setCount(2);
        var analysis = new SessionAnalysis(); analysis.setGenreScores(List.of(score));
        var recommendations = List.of(recommendation());
        when(generator.generate(session)).thenReturn(new RecommendationGenerator.Result(recommendations, analysis));
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(ID))
                .andExpect(jsonPath("$.recommendations[0].title").value("매트릭스"))
                .andExpect(jsonPath("$.analysis.genreScores[0].count").value(2));
        verify(results).saveRecommendations(OWNER, ID, recommendations, analysis);
    }

    @Test
    void foreignSessionNeverStartsGeneration() throws Exception {
        when(sessions.findById(ID)).thenReturn(Optional.of(session()));
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OTHER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        verifyNoInteractions(generator, results);
    }

    @Test
    void existingResultsAvoidExternalGeneration() throws Exception {
        var session = session(); session.setRecommendations(List.of(recommendation()));
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RECOMMENDATION_EXISTS"));
        verifyNoInteractions(generator, results);
    }

    @Test
    void generationFailureStoresNothing() throws Exception {
        var session = session();
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
        when(generator.generate(session)).thenThrow(ApiException.upstream());
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
        verifyNoInteractions(results);
        assertThat(session.getRecommendations()).isEmpty();
    }
}
