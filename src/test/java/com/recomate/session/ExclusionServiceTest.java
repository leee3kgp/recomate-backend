package com.recomate.session;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.dto.ExclusionRequest;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.RecommendationRepository.Exclusion;
import com.recomate.session.repository.SessionRepository;
import com.recomate.session.service.ExclusionService;
import com.recomate.session.service.RecommendationGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExclusionServiceTest {
    private static final String OWNER = "owner-sub";
    private static final String ID = "01234567-89ab-4cde-8fab-0123456789ab";
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RecommendationRepository results = mock(RecommendationRepository.class);
    private final RecommendationGenerator generator = mock(RecommendationGenerator.class);
    private final ExclusionService service = new ExclusionService(sessions, results, generator);
    private final SessionAnalysis recomputed = new SessionAnalysis();

    @BeforeEach
    void defaults() {
        when(generator.analyze(any(), anyList())).thenReturn(recomputed);
        when(results.saveExclusion(anyString(), anyString(), any(), any())).thenReturn(true);
    }

    private static Recommendation title(Integer rank, long id, String type) {
        var value = new Recommendation();
        value.setRank(rank); value.setTmdbId(id); value.setMediaType(type); value.setGenres(List.of("SF"));
        return value;
    }

    private RecommendationSession stored(Long version, List<Recommendation> current, List<Recommendation> candidates,
            List<String> excluded) {
        var value = new RecommendationSession();
        value.setSessionId(ID); value.setUserId(OWNER); value.setVersion(version);
        value.setRecommendations(new ArrayList<>(current)); value.setCandidates(candidates); value.setExcluded(excluded);
        when(sessions.findById(ID)).thenReturn(Optional.of(value));
        return value;
    }

    private Exclusion saved(Long expectedVersion) {
        var captor = ArgumentCaptor.forClass(Exclusion.class);
        verify(results).saveExclusion(eq(OWNER), eq(ID), eq(expectedVersion), captor.capture());
        return captor.getValue();
    }

    private static List<String> keys(List<Recommendation> values) {
        return values.stream().map(v -> v.getRank() + ":" + v.getMediaType() + ":" + v.getTmdbId()).toList();
    }

    @Test
    void excludedTitleIsReplacedByTheBestCandidateAndRanksAreRenumbered() {
        stored(1L, List.of(title(1, 10, "movie"), title(2, 20, "tv"), title(3, 30, "movie")),
                List.of(title(null, 40, "movie"), title(null, 50, "tv")), null);

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie"));

        assertThat(keys(response.recommendations())).containsExactly("1:tv:20", "2:movie:30", "3:movie:40");
        assertThat(response.analysis()).isSameAs(recomputed);
        var exclusion = saved(1L);
        assertThat(exclusion.candidates()).extracting(Recommendation::getTmdbId).containsExactly(50L);
        assertThat(exclusion.excluded()).containsExactly("movie:10");
        verify(generator).analyze(any(), eq(exclusion.recommendations()));
    }

    @Test
    void sameIdWithAnotherMediaTypeIsADifferentTitle() {
        stored(1L, List.of(title(1, 10, "movie"), title(2, 10, "tv")), List.of(), null);

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "tv"));

        assertThat(keys(response.recommendations())).containsExactly("1:movie:10");
    }

    @Test
    void exhaustedCandidatesReturnTheRemainingTitlesOnly() {
        stored(2L, List.of(title(1, 10, "movie")), List.of(), List.of("tv:99"));

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie"));

        assertThat(response.recommendations()).isEmpty();
        assertThat(saved(2L).excluded()).containsExactly("tv:99", "movie:10");
    }

    @Test
    void legacySessionsWithoutCandidatesOrVersionStillExclude() {
        stored(null, List.of(title(1, 10, "movie"), title(2, 20, "movie")), null, null);

        var response = service.exclude(OWNER, ID, new ExclusionRequest(20L, "movie"));

        assertThat(keys(response.recommendations())).containsExactly("1:movie:10");
        assertThat(saved(null).candidates()).isEmpty();
    }

    @Test
    void candidatesAlreadyExcludedOrShownAreSkipped() {
        stored(1L, List.of(title(1, 10, "movie"), title(2, 20, "movie")),
                List.of(title(null, 20, "movie"), title(null, 30, "movie"), title(null, 40, "movie")), List.of("movie:30"));

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie"));

        assertThat(keys(response.recommendations())).containsExactly("1:movie:20", "2:movie:40");
        assertThat(saved(1L).candidates()).isEmpty();
    }

    @Test
    void repeatedExclusionReturnsCurrentStateWithoutWriting() {
        var analysis = new SessionAnalysis();
        var session = stored(2L, List.of(title(1, 20, "movie")), List.of(), List.of("movie:10"));
        session.setAnalysis(analysis);

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie"));

        assertThat(keys(response.recommendations())).containsExactly("1:movie:20");
        assertThat(response.analysis()).isSameAs(analysis);
        verify(results, never()).saveExclusion(any(), any(), any(), any());
    }

    @Test
    void titleOutsideCurrentResultsIsRejectedBeforeWriting() {
        stored(1L, List.of(title(1, 10, "movie")), List.of(title(null, 40, "movie")), null);

        assertThatThrownBy(() -> service.exclude(OWNER, ID, new ExclusionRequest(40L, "movie")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(400));
        verify(results, never()).saveExclusion(any(), any(), any(), any());
    }

    @Test
    void foreignAndMissingSessionsLookTheSame() {
        var foreign = new RecommendationSession(); foreign.setSessionId(ID); foreign.setUserId("other-sub");
        when(sessions.findById(ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        verifyNoInteractions(results, generator);
    }

    @Test
    void lostRaceRereadsTheNewerStateOnce() {
        var first = new RecommendationSession();
        first.setSessionId(ID); first.setUserId(OWNER); first.setVersion(1L);
        first.setRecommendations(List.of(title(1, 10, "movie"), title(2, 20, "movie")));
        var newer = new RecommendationSession();
        newer.setSessionId(ID); newer.setUserId(OWNER); newer.setVersion(2L);
        newer.setRecommendations(List.of(title(1, 10, "movie")));
        newer.setExcluded(List.of("movie:20"));
        when(sessions.findById(ID)).thenReturn(Optional.of(first)).thenReturn(Optional.of(newer));
        when(results.saveExclusion(eq(OWNER), eq(ID), eq(1L), any())).thenReturn(false);

        var response = service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie"));

        assertThat(response.recommendations()).isEmpty();
        assertThat(saved(2L).excluded()).containsExactly("movie:20", "movie:10");
    }

    @Test
    void repeatedLostRacesReportAConflict() {
        stored(1L, List.of(title(1, 10, "movie")), List.of(), null);
        when(results.saveExclusion(anyString(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.exclude(OWNER, ID, new ExclusionRequest(10L, "movie")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("CONCURRENT_UPDATE"));
        verify(results, times(2)).saveExclusion(any(), any(), any(), any());
    }

    @Test
    void malformedSessionIdNeverReachesStorage() {
        assertThatThrownBy(() -> service.exclude(OWNER, "not-a-uuid", new ExclusionRequest(10L, "movie")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(400));
        verifyNoInteractions(sessions, results, generator);
    }
}
