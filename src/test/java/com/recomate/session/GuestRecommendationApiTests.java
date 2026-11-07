package com.recomate.session;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.SessionRepository;
import com.recomate.session.service.RecommendationGenerator;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GuestRecommendationApiTests {
    private static final String PATH = "/api/guest/recommendations";
    private static final String PARTICIPANTS = "\"participantCount\":2,\"participants\":["
            + "{\"index\":1,\"genres\":[\"SF\",\"ACTION\"]},{\"index\":2,\"genres\":[\"COMEDY\"]}]";
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
        // Titles 1..3 are the TOP 3 and 4..5 the next candidates, mirroring the generator's score order.
        when(generator.generate(any())).thenAnswer(call -> new RecommendationGenerator.Result(
                IntStream.rangeClosed(1, 3).mapToObj(id -> title(id, id)).toList(), analysis(0.9),
                IntStream.rangeClosed(4, 5).mapToObj(id -> title(id, null)).toList()));
        when(generator.analyze(any(), anyList())).thenReturn(analysis(0.5));
    }

    private static Recommendation title(long id, Integer rank) {
        var value = new Recommendation();
        value.setRank(rank); value.setTmdbId(id); value.setMediaType("movie"); value.setTitle("작품 " + id);
        return value;
    }

    private static SessionAnalysis analysis(double rate) {
        var match = new SessionAnalysis.ParticipantMatch();
        match.setIndex(1); match.setMatchRate(rate);
        var value = new SessionAnalysis();
        value.setParticipantMatch(List.of(match));
        return value;
    }

    private org.springframework.test.web.servlet.ResultActions post(String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void guestGetsTopThreeWithoutLoginAndNothingIsStored() throws Exception {
        post("{" + PARTICIPANTS + "}")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.recommendations.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0].tmdbId").value(1))
                .andExpect(jsonPath("$.analysis.participantMatch[0].matchRate").value(0.9));
        var session = ArgumentCaptor.forClass(RecommendationSession.class);
        verify(generator).generate(session.capture());
        assertThat(session.getValue().getUserId()).isNull();
        assertThat(session.getValue().getParticipants()).extracting("index").containsExactly(1, 2);
        verify(generator, never()).analyze(any(), anyList());
        verifyNoInteractions(sessions, results, users, identities, eraser);
    }

    @Test
    void seenTitlesAreReplacedByTheNextCandidatesWithoutStoringAnything() throws Exception {
        post("{" + PARTICIPANTS + ",\"excluded\":[{\"tmdbId\":2,\"mediaType\":\"movie\"},{\"tmdbId\":4,\"mediaType\":\"movie\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].tmdbId").value(org.hamcrest.Matchers.contains(1, 3, 5)))
                .andExpect(jsonPath("$.recommendations[*].rank").value(org.hamcrest.Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$.analysis.participantMatch[0].matchRate").value(0.5));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Recommendation>> shown = ArgumentCaptor.forClass(List.class);
        verify(generator).analyze(any(), shown.capture());
        assertThat(shown.getValue()).extracting(Recommendation::getTmdbId).containsExactly(1L, 3L, 5L);
        verifyNoInteractions(sessions, results, users, identities, eraser);
    }

    @Test
    void sameIdWithAnotherMediaTypeIsNotExcluded() throws Exception {
        post("{" + PARTICIPANTS + ",\"excluded\":[{\"tmdbId\":2,\"mediaType\":\"tv\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].tmdbId").value(org.hamcrest.Matchers.contains(1, 2, 3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"participantCount\":3,\"participants\":[{\"index\":1,\"genres\":[\"SF\"]},{\"index\":2,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[\"SF\"]},{\"index\":1,\"genres\":[\"SF\"]}]}",
            "{\"participantCount\":2,\"participants\":[{\"index\":1,\"genres\":[\"WESTERN\"]},{\"index\":2,\"genres\":[\"SF\"]}]}"})
    void invalidParticipantsAreRejectedBeforeAnyTmdbCall(String body) throws Exception {
        post(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"participantCount\":1,\"participants\":[{\"index\":1,\"genres\":[\"SF\"]}]}",
            "{" + PARTICIPANTS + ",\"excluded\":[{\"tmdbId\":2,\"mediaType\":\"book\"}]}",
            "{" + PARTICIPANTS + ",\"excluded\":[{\"tmdbId\":-2,\"mediaType\":\"movie\"}]}",
            "not-json"})
    void malformedRequestsAreRejected(String body) throws Exception {
        post(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(generator);
    }

    @Test
    void excludedListIsBounded() throws Exception {
        String twentyOne = IntStream.rangeClosed(1, 21).mapToObj(id -> "{\"tmdbId\":" + id + ",\"mediaType\":\"movie\"}")
                .collect(java.util.stream.Collectors.joining(","));
        post("{" + PARTICIPANTS + ",\"excluded\":[" + twentyOne + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(generator);
    }

    @Test
    void onlyThePostIsPublicAndMemberRoutesStillNeedLogin() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + PARTICIPANTS + "}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(generator, sessions);
    }

    @Test
    void browserPreflightAndCorsWorkWithoutLogin() throws Exception {
        mvc.perform(options(PATH).header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH)
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + PARTICIPANTS + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
