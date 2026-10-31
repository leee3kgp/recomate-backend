package com.recomate.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.session.domain.Participant;
import com.recomate.session.domain.RecommendationSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TmdbRecommendationGeneratorTest {
    private final RecommendationScorer scorer = new RecommendationScorer();

    private static RecommendationSession session() {
        var value = new RecommendationSession();
        value.setParticipants(List.of(new Participant(1, List.of("ACTION", "SF")), new Participant(2, List.of("SF"))));
        return value;
    }

    private static TmdbContent content(long id, ContentType type, Genre... genres) {
        return new TmdbContent(id, type, "title" + id, "/poster.jpg", 2024, 8.0, 1000, 50, Set.of(genres));
    }

    @Test
    void combinesMovieAndTvCandidatesIntoRankedResultsAndAnalysis() {
        List<ContentType> requested = new ArrayList<>();
        TmdbClient tmdb = (type, genres) -> {
            requested.add(type);
            assertEquals(Set.of(Genre.ACTION, Genre.SF), Set.copyOf(genres));
            return type == ContentType.MOVIE ? List.of(content(1, type, Genre.SF, Genre.ACTION))
                    : List.of(content(2, type, Genre.ACTION));
        };

        var result = new TmdbRecommendationGenerator(tmdb, scorer).generate(session());

        assertEquals(List.of(ContentType.MOVIE, ContentType.TV), requested);
        var first = result.recommendations().get(0);
        assertEquals(1, first.getRank());
        assertEquals(1L, first.getTmdbId());
        assertEquals("movie", first.getMediaType());
        assertEquals(List.of("ACTION", "SF"), first.getGenres());
        assertEquals(List.of(1, 2), first.getMatchedParticipants());
        var second = result.recommendations().get(1);
        assertEquals(2, second.getRank());
        assertEquals("tv", second.getMediaType());
        assertEquals(List.of(1), second.getMatchedParticipants());
        assertEquals("SF", result.analysis().getGenreScores().get(0).getGenre());
        assertEquals(2, result.analysis().getGenreScores().get(0).getCount());
        assertEquals(1.0, result.analysis().getParticipantMatch().get(0).getMatchRate());
        assertEquals(0.5, result.analysis().getParticipantMatch().get(1).getMatchRate());
    }

    @Test
    void noCandidatesProduceEmptyResultsWithZeroMatchRate() {
        var result = new TmdbRecommendationGenerator((type, genres) -> List.of(), scorer).generate(session());

        assertTrue(result.recommendations().isEmpty());
        assertEquals(0.0, result.analysis().getParticipantMatch().get(0).getMatchRate());
        assertEquals(2, result.analysis().getGenreScores().get(0).getCount());
    }

    @Test
    void upstreamFailureIsNotHidden() {
        TmdbClient failing = (type, genres) -> { throw ApiException.upstream(); };

        assertThrows(ApiException.class, () -> new TmdbRecommendationGenerator(failing, scorer).generate(session()));
    }
}
