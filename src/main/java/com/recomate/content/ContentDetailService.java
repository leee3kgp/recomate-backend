package com.recomate.content;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.recomate.common.ApiException;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContentDetail;
import com.recomate.watched.WatchedRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 작품 상세 조회. TMDB 데이터는 모든 사용자에게 같으므로 "mediaType:tmdbId"를 키로 인스턴스 메모리에 잠시 보관하고,
 * 사용자마다 다른 watched 값은 캐시하지 않고 요청마다 조회한다.
 */
@Service
public class ContentDetailService {
    private static final int MAX_CACHED_TITLES = 1000;

    private final TmdbClient tmdb;
    private final WatchedRepository watched;
    private final Cache<String, TmdbContentDetail> cache;

    @Autowired
    public ContentDetailService(TmdbClient tmdb, WatchedRepository watched,
                                @Value("${recomate.content.cache-minutes:30}") long cacheMinutes) {
        this(tmdb, watched, Duration.ofMinutes(cacheMinutes), Ticker.systemTicker());
    }

    ContentDetailService(TmdbClient tmdb, WatchedRepository watched, Duration ttl, Ticker ticker) {
        this.tmdb = tmdb;
        this.watched = watched;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(MAX_CACHED_TITLES).ticker(ticker).build();
    }

    /** userId는 검증된 JWT의 sub다. */
    public ContentDetailResponse detail(String userId, String rawMediaType, String rawTmdbId) {
        if (rawMediaType == null || !rawMediaType.matches("movie|tv")
                || rawTmdbId == null || !rawTmdbId.matches("[1-9][0-9]{0,17}")) {
            throw ApiException.validation("콘텐츠 유형과 ID를 확인해주세요.");
        }
        ContentType type = "movie".equals(rawMediaType) ? ContentType.MOVIE : ContentType.TV;
        long id = Long.parseLong(rawTmdbId);
        String key = type.value() + ":" + id;
        // A missing title (404) or TMDB failure (502) throws inside the loader, so nothing is cached and the next request retries.
        TmdbContentDetail detail = cache.get(key,
                ignored -> tmdb.contentDetail(type, id).orElseThrow(ApiException::contentNotFound));
        return ContentDetailResponse.of(detail, watched.contains(userId, key));
    }

    void clearCache() {
        cache.invalidateAll();
    }
}
