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
    void matchRateAveragesEachTitlesGenreOverlapAcrossTitles() {
        var session = new RecommendationSession();
        session.setParticipants(List.of(new Participant(1, List.of("ACTION", "SF")),
                new Participant(2, List.of("COMEDY", "DRAMA")),
                new Participant(3, List.of("ACTION", "COMEDY", "ANIMATION"))));
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE ? List.of(
                content(1, type, Genre.ACTION, Genre.DRAMA),
                content(2, type, Genre.ACTION, Genre.DRAMA, Genre.SF, Genre.FANTASY, Genre.CRIME),
                content(3, type, Genre.ACTION, Genre.COMEDY)) : List.of();

        var matches = new TmdbRecommendationGenerator(tmdb, scorer).generate(session).analysis().getParticipantMatch();

        assertEquals(0.67, matches.get(0).getMatchRate());
        assertEquals(0.5, matches.get(1).getMatchRate());
        assertEquals(0.61, matches.get(2).getMatchRate());
    }

    @Test
    void matchRateIsNotCappedForParticipantsWhoChooseManyGenres() {
        var session = new RecommendationSession();
        session.setParticipants(List.of(new Participant(1, List.of("ACTION")),
                new Participant(2, List.of("ACTION", "SF", "COMEDY", "DRAMA", "ROMANCE")),
                new Participant(3, List.of("ACTION", "COMEDY", "DRAMA", "ROMANCE", "HORROR")),
                new Participant(4, List.of("COMEDY", "DRAMA"))));
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE
                ? List.of(content(1, type, Genre.ACTION, Genre.SF)) : List.of();

        var matches = new TmdbRecommendationGenerator(tmdb, scorer).generate(session).analysis().getParticipantMatch();

        assertEquals(1.0, matches.get(0).getMatchRate());
        assertEquals(1.0, matches.get(1).getMatchRate());
        assertEquals(0.5, matches.get(2).getMatchRate());
        assertEquals(0.0, matches.get(3).getMatchRate());
    }

    @Test
    void matchRateIsZeroWithoutSelectedGenres() {
        var session = new RecommendationSession();
        session.setParticipants(List.of(new Participant(1, List.of("ACTION")), new Participant(2, List.of())));
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE
                ? List.of(content(1, type, Genre.ACTION)) : List.of();

        var matches = new TmdbRecommendationGenerator(tmdb, scorer).generate(session).analysis().getParticipantMatch();

        assertEquals(1.0, matches.get(0).getMatchRate());
        assertEquals(0.0, matches.get(1).getMatchRate());
    }

    @Test
    void noCandidatesProduceEmptyResultsWithZeroMatchRate() {
        var result = new TmdbRecommendationGenerator((type, genres) -> List.of(), scorer).generate(session());

        assertTrue(result.recommendations().isEmpty());
        assertEquals(0.0, result.analysis().getParticipantMatch().get(0).getMatchRate());
        assertEquals(2, result.analysis().getGenreScores().get(0).getCount());
    }

    @Test
    void titlesAfterTopThreeAreKeptAsUnrankedCandidatesInScoreOrder() {
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE ? List.of(
                content(1, type, Genre.ACTION, Genre.SF), content(2, type, Genre.SF),
                content(3, type, Genre.ACTION), content(4, type, Genre.SF, Genre.ACTION),
                new TmdbContent(5, type, "title5", null, 2020, 2.0, 1000, 1, Set.of(Genre.ACTION))) : List.of();

        var result = new TmdbRecommendationGenerator(tmdb, scorer).generate(session());

        assertEquals(List.of(1L, 4L, 2L), result.recommendations().stream().map(r -> r.getTmdbId()).toList());
        assertEquals(List.of(3L, 5L), result.candidates().stream().map(r -> r.getTmdbId()).toList());
        assertTrue(result.candidates().stream().allMatch(c -> c.getRank() == null && c.getGenres() != null));
        assertTrue(result.candidates().get(0).getScore() >= result.candidates().get(1).getScore());
    }

    @Test
    void candidateListIsBoundedForTheSessionItem() {
        List<TmdbContent> many = new ArrayList<>();
        for (long id = 1; id <= 40; id++) many.add(content(id, ContentType.MOVIE, Genre.SF));
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE ? many : List.of();

        var result = new TmdbRecommendationGenerator(tmdb, scorer).generate(session());

        assertEquals(3, result.recommendations().size());
        assertEquals(TmdbRecommendationGenerator.CANDIDATE_LIMIT, result.candidates().size());
    }

    @Test
    void analyzeUsesStoredGenresAndMatchesGeneration() {
        var session = session();
        TmdbClient tmdb = (type, genres) -> type == ContentType.MOVIE
                ? List.of(content(1, type, Genre.SF, Genre.ACTION), content(2, type, Genre.ACTION)) : List.of();
        var generator = new TmdbRecommendationGenerator(tmdb, scorer);
        var generated = generator.generate(session);

        var recomputed = generator.analyze(session, generated.recommendations());

        assertEquals(generated.analysis().getParticipantMatch().get(0).getMatchRate(),
                recomputed.getParticipantMatch().get(0).getMatchRate());
        assertEquals(generated.analysis().getParticipantMatch().get(1).getMatchRate(),
                recomputed.getParticipantMatch().get(1).getMatchRate());
        assertEquals(0.0, generator.analyze(session, List.of()).getParticipantMatch().get(0).getMatchRate());
    }

    @Test
    void upstreamFailureIsNotHidden() {
        TmdbClient failing = (type, genres) -> { throw ApiException.upstream(); };

        assertThrows(ApiException.class, () -> new TmdbRecommendationGenerator(failing, scorer).generate(session()));
    }
}
