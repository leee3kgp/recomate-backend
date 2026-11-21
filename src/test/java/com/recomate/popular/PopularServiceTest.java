package com.recomate.popular;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.DiscoverSort;
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
        when(tmdb.discoverPopular(any(), any(), any(), any())).thenReturn(List.of());
        service.popular("action", "kr", null, null);
        service.popular("ACTION", "KR", null, null); // 정규화하면 같은 키
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, Genre.ACTION, "KR", DiscoverSort.POPULAR);
        verify(tmdb, times(1)).discoverPopular(ContentType.TV, Genre.ACTION, "KR", DiscoverSort.POPULAR);
    }

    @Test
    void differentCombinationsAreCachedSeparately() {
        when(tmdb.discoverPopular(any(), any(), any(), any())).thenReturn(List.of());
        service.popular("ACTION", null, null, null);
        service.popular("ACTION", "KR", null, null);
        service.popular(null, "KR", null, null);
        service.popular(null, "KR", null, null); // 네 번째 호출은 캐시 사용
        verify(tmdb, times(3)).discoverPopular(eq(ContentType.MOVIE), any(), any(), any());
    }

    @Test
    void entryExpiresAfterTtl() {
        when(tmdb.discoverPopular(any(), any(), any(), any())).thenReturn(List.of());
        service.popular(null, null, null, null);
        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        service.popular(null, null, null, null);
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.POPULAR);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos()); // 총 11분 경과
        service.popular(null, null, null, null);
        verify(tmdb, times(2)).discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.POPULAR);
    }

    @Test
    void resultIsSortedByPopularityAndLimitedToTwenty() {
        when(tmdb.discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.POPULAR)).thenReturn(
                IntStream.range(0, 15).mapToObj(i -> content(i, ContentType.MOVIE, i)).toList());
        when(tmdb.discoverPopular(ContentType.TV, null, null, DiscoverSort.POPULAR)).thenReturn(
                IntStream.range(100, 115).mapToObj(i -> content(i, ContentType.TV, i)).toList());

        var items = service.popular(null, null, null, null).items();

        assertThat(items).hasSize(PopularService.LIMIT);
        assertThat(items.get(0).id()).isEqualTo(114); // 가장 인기 있는 TV
        assertThat(items.get(0).type()).isEqualTo("tv");
    }

    @Test
    void omittedSortSharesCacheWithExplicitPopular() {
        when(tmdb.discoverPopular(any(), any(), any(), any())).thenReturn(List.of());
        service.popular(null, null, null, null);
        service.popular(null, null, null, "popular"); // 생략과 기본값은 같은 키
        service.popular(null, null, null, " POPULAR ");
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.POPULAR);
    }

    @Test
    void typeAndSortAreSeparateCacheKeys() {
        when(tmdb.discoverPopular(any(), any(), any(), any())).thenReturn(List.of());
        service.popular(null, null, "movie", "rating");
        service.popular(null, null, "movie", "latest");
        service.popular(null, null, null, "rating"); // 종류 생략은 movie와 다른 키
        service.popular(null, null, "MOVIE", "RATING"); // 캐시 사용
        verify(tmdb, times(2)).discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.RATING);
        verify(tmdb, times(1)).discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.LATEST);
        verify(tmdb, times(1)).discoverPopular(ContentType.TV, null, null, DiscoverSort.RATING);
    }

    @Test
    void ratingMergesByVoteAverageThenVoteCount() {
        when(tmdb.discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.RATING)).thenReturn(List.of(
                new TmdbContent(1, ContentType.MOVIE, "영화", null, 2024, 8.5, 300, 10, Set.of(Genre.DRAMA)),
                new TmdbContent(2, ContentType.MOVIE, "영화2", null, 2024, 7.0, 9000, 99, Set.of(Genre.DRAMA))));
        when(tmdb.discoverPopular(ContentType.TV, null, null, DiscoverSort.RATING)).thenReturn(List.of(
                new TmdbContent(3, ContentType.TV, "드라마", null, 2024, 8.5, 5000, 1, Set.of(Genre.DRAMA))));

        var items = service.popular(null, null, null, "rating").items();

        assertThat(items).extracting(PopularResponse.Item::id).containsExactly(3L, 1L, 2L);
    }
}
