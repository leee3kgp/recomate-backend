package com.recomate.session;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.repository.DynamoSessionRepository;
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

    private Recommendation recommendation() {
        var item = new Recommendation();
        item.setRank(1); item.setTmdbId(603L); item.setMediaType("movie");
        return item;
    }
}
