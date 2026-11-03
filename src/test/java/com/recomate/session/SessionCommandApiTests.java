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
    private static final String EXCLUSIONS = "/api/sessions/" + ID + "/exclusions";
    private static final String EXCLUSION_BODY = "{\"tmdbId\":603,\"mediaType\":\"movie\"}";
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
        for (var request : List.of(delete("/api/sessions/" + ID), post(RECOMMENDATIONS),
                post(EXCLUSIONS).contentType("application/json").content(EXCLUSION_BODY))) {
            mvc.perform(request.with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(sessions, results, generator);
    }

    @Test
    void commandRoutesRequireAuthenticationAndAllowPreflight() throws Exception {
        for (var request : List.of(delete("/api/sessions/" + ID), post(RECOMMENDATIONS),
                post(EXCLUSIONS).contentType("application/json").content(EXCLUSION_BODY))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        for (var entry : List.of(List.of("/api/sessions/" + ID, "DELETE"), List.of(RECOMMENDATIONS, "POST"),
                List.of(EXCLUSIONS, "POST"))) {
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
        var candidate = recommendation(); candidate.setRank(null); candidate.setTmdbId(604L);
        when(generator.generate(session)).thenReturn(
                new RecommendationGenerator.Result(recommendations, analysis, List.of(candidate)));
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(ID))
                .andExpect(jsonPath("$.recommendations[0].title").value("매트릭스"))
                .andExpect(jsonPath("$.analysis.genreScores[0].count").value(2))
                .andExpect(jsonPath("$.candidates").doesNotExist());
        verify(results).saveRecommendations(OWNER, ID, recommendations, analysis, List.of(candidate));
    }

    @Test
    void sessionsThatAlreadyGeneratedCannotRegenerateAfterExcludingEverything() throws Exception {
        var session = session(); session.setVersion(3L);
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
        mvc.perform(post(RECOMMENDATIONS).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RECOMMENDATION_EXISTS"));
        verifyNoInteractions(generator, results);
    }

    @Test
    void exclusionReturnsRefreshedResultsWithoutInternalState() throws Exception {
        var session = session(); session.setVersion(1L);
        var shown = recommendation();
        var next = recommendation(); next.setRank(null); next.setTmdbId(604L); next.setTitle("매트릭스 2");
        session.setRecommendations(List.of(shown)); session.setCandidates(List.of(next));
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
        when(generator.analyze(any(), anyList())).thenReturn(new SessionAnalysis());
        when(results.saveExclusion(eq(OWNER), eq(ID), eq(1L), any())).thenReturn(true);
        mvc.perform(post(EXCLUSIONS).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json")
                        .content("{\"tmdbId\":603,\"mediaType\":\"movie\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionId").value(ID))
                .andExpect(jsonPath("$.recommendations.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].rank").value(1))
                .andExpect(jsonPath("$.recommendations[0].title").value("매트릭스 2"))
                .andExpect(jsonPath("$.candidates").doesNotExist()).andExpect(jsonPath("$.excluded").doesNotExist());
        verifyNoMoreInteractions(ignoreStubs(generator));
        verify(generator, never()).generate(any());
    }

    @Test
    void malformedExclusionBodiesAreRejectedBeforeStorage() throws Exception {
        for (String body : List.of("", "{}", "{\"tmdbId\":603}", "{\"tmdbId\":0,\"mediaType\":\"movie\"}",
                "{\"tmdbId\":603,\"mediaType\":\"MOVIE\"}", "{\"tmdbId\":\"abc\",\"mediaType\":\"tv\"}")) {
            mvc.perform(post(EXCLUSIONS).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(sessions, results, generator);
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
