package com.recomate.popular;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PopularServiceTest {
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final AtomicLong nanos = new AtomicLong();
    private final PopularService service = new PopularService(tmdb, Duration.ofMinutes(10), nanos::get);

    private static TmdbContent content(long id, ContentType type, double popularity) {
        return new TmdbContent(id, type, "작품 " + id, null, 2024, 7.0, 500, popularity, Set.of(Genre.DRAMA));
    }

    @Test
    void sameFilterCombinationHitsTmdbOnlyOnce() {
        when(tmdb.discoverPopular(any(), any(), any())).thenReturn(List.of());
        service.popular("action", "kr");
        service.popular("ACTION", "KR"); // 정규화하면 같은 키
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, Genre.ACTION, "KR");
        verify(tmdb, times(1)).discoverPopular(ContentType.TV, Genre.ACTION, "KR");
    }

    @Test
    void differentCombinationsAreCachedSeparately() {
        when(tmdb.discoverPopular(any(), any(), any())).thenReturn(List.of());
        service.popular("ACTION", null);
        service.popular("ACTION", "KR");
        service.popular(null, "KR");
        service.popular(null, "KR"); // 네 번째 호출은 캐시 사용
        verify(tmdb, times(3)).discoverPopular(eq(ContentType.MOVIE), any(), any());
    }

    @Test
    void entryExpiresAfterTtl() {
        when(tmdb.discoverPopular(any(), any(), any())).thenReturn(List.of());
        service.popular(null, null);
        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        service.popular(null, null);
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, null, null);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos()); // 총 11분 경과
        service.popular(null, null);
        verify(tmdb, times(2)).discoverPopular(ContentType.MOVIE, null, null);
    }

    @Test
    void resultIsSortedByPopularityAndLimitedToTwenty() {
        when(tmdb.discoverPopular(ContentType.MOVIE, null, null)).thenReturn(
                IntStream.range(0, 15).mapToObj(i -> content(i, ContentType.MOVIE, i)).toList());
        when(tmdb.discoverPopular(ContentType.TV, null, null)).thenReturn(
                IntStream.range(100, 115).mapToObj(i -> content(i, ContentType.TV, i)).toList());

        var items = service.popular(null, null).items();

        assertThat(items).hasSize(PopularService.LIMIT);
        assertThat(items.get(0).id()).isEqualTo(114); // 가장 인기 있는 TV
        assertThat(items.get(0).type()).isEqualTo("tv");
    }
}
