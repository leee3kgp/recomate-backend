package com.recomate.session;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.repository.DynamoSessionRepository;
import com.recomate.session.repository.RecommendationRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationValidationTests {
    @Test
    void invalidResultsNeverReachTheDatabase() {
        var db = mock(DynamoDbClient.class);
        var repository = new DynamoSessionRepository(db, "sessions", "users");
        for (String mediaType : Arrays.asList(null, "MOVIE", "unknown")) {
            var item = recommendation(); item.setMediaType(mediaType);
            assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", List.of(item)))
                    .isInstanceOf(ApiException.class);
        }
        var item = recommendation(); item.setRank(2);
        assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", List.of(item)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", Arrays.asList((Recommendation) null)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(db);
    }

    @Test
    void invalidOptionalValuesNeverReachTheDatabase() {
        var db = mock(DynamoDbClient.class);
        var repository = new DynamoSessionRepository(db, "sessions", "users");
        var genre = recommendation(); genre.setGenres(List.of("NOT_A_GENRE"));
        var nullGenre = recommendation(); nullGenre.setGenres(Arrays.asList((String) null));
        var participant = recommendation(); participant.setMatchedParticipants(List.of(7));
        for (var item : List.of(genre, nullGenre, participant)) {
            assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", List.of(item)))
                    .isInstanceOf(ApiException.class);
        }
        var score = new SessionAnalysis.GenreScore(); score.setGenre("SF"); score.setCount(-1);
        var scores = new SessionAnalysis(); scores.setGenreScores(List.of(score));
        var match = new SessionAnalysis.ParticipantMatch(); match.setIndex(1); match.setMatchRate(1.5);
        var matches = new SessionAnalysis(); matches.setParticipantMatch(List.of(match));
        for (var analysis : List.of(scores, matches)) {
            assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", List.of(recommendation()), analysis))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(db);
    }

    @Test
    void invalidCandidatesAndExclusionsNeverReachTheDatabase() {
        var db = mock(DynamoDbClient.class);
        var repository = new DynamoSessionRepository(db, "sessions", "users");
        var badType = recommendation(); badType.setMediaType("MOVIE");
        var badGenre = recommendation(); badGenre.setGenres(List.of("NOT_A_GENRE"));
        var tooMany = java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(id -> {
            var item = recommendation(); item.setTmdbId(id); return item;
        }).toList();
        for (var candidates : List.of(List.of(badType), List.of(badGenre), List.of(recommendation(), recommendation()), tooMany)) {
            assertThatThrownBy(() -> repository.saveRecommendations("owner", "session", List.of(), null, candidates))
                    .isInstanceOf(ApiException.class);
        }
        for (var excluded : List.of(List.of("movie:0"), List.of("MOVIE:1"), List.of("movie:1", "movie:1"), List.of("1"))) {
            var exclusion = new RecommendationRepository.Exclusion(List.of(), null, List.of(), excluded);
            assertThatThrownBy(() -> repository.saveExclusion("owner", "session", 1L, exclusion))
                    .isInstanceOf(ApiException.class);
        }
        var ranked = recommendation(); ranked.setRank(2);
        assertThatThrownBy(() -> repository.saveExclusion("owner", "session", 1L,
                new RecommendationRepository.Exclusion(List.of(ranked), null, List.of(), List.of())))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(db);
    }

    private Recommendation recommendation() {
        var item = new Recommendation();
        item.setRank(1); item.setTmdbId(603L); item.setMediaType("movie");
        return item;
    }
}
