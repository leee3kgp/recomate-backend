package com.recomate.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recomate.genre.Genre;
import com.recomate.recommendation.RecommendationScorer.Scored;
import com.recomate.recommendation.tmdb.TmdbContent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationScorerTest {
    private final RecommendationScorer scorer = new RecommendationScorer();

    private static TmdbContent content(long id, double rating, double popularity, Genre... genres) {
        return new TmdbContent(id, ContentType.MOVIE, "title" + id, null, 2024, rating, 1000, popularity, Set.of(genres));
    }

    private static final Map<Integer, Set<Genre>> GROUP =
            Map.of(1, Set.of(Genre.ACTION), 2, Set.of(Genre.DRAMA));

    @Test
    void contentMatchingEveryoneBeatsHighRatedContentMatchingOne() {
        TmdbContent all = content(1, 6.0, 50, Genre.ACTION, Genre.DRAMA);
        TmdbContent one = content(2, 9.5, 50, Genre.ACTION);

        List<Scored> result = scorer.top(GROUP, List.of(one, all), 3);

        assertEquals(List.of(1L, 2L), result.stream().map(s -> s.content().id()).toList());
        assertEquals(List.of(1, 2), result.get(0).matchedParticipants());
        assertEquals(List.of(1), result.get(1).matchedParticipants());
    }

    @Test
    void excludesContentMatchingNobody() {
        TmdbContent none = content(3, 9.0, 80, Genre.HORROR);

        assertTrue(scorer.top(GROUP, List.of(none), 3).isEmpty());
    }

    @Test
    void removesDuplicateContent() {
        TmdbContent a = content(4, 8.0, 10, Genre.ACTION);

        assertEquals(1, scorer.top(GROUP, List.of(a, a), 3).size());
    }

    @Test
    void returnsAtMostLimit() {
        List<TmdbContent> many = List.of(
                content(5, 7.0, 10, Genre.ACTION), content(6, 7.1, 10, Genre.ACTION),
                content(7, 7.2, 10, Genre.ACTION), content(8, 7.3, 10, Genre.ACTION));

        assertEquals(3, scorer.top(GROUP, many, 3).size());
    }

    @Test
    void scoreStaysWithinZeroToHundred() {
        Scored s = scorer.top(GROUP, List.of(content(9, 10.0, 100, Genre.ACTION, Genre.DRAMA)), 1).get(0);

        assertEquals(100.0, s.score());
    }
}
