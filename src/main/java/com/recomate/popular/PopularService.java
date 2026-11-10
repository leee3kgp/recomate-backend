package com.recomate.popular;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.recommendation.tmdb.TmdbGenreMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 장르·국가 필터별 인기 목록. 결과는 필터 조합을 키로 인스턴스 메모리에 잠시 보관한다.
 * 모든 사용자에게 같은 데이터이므로 사용자 정보는 키에 넣지 않는다.
 */
@Service
public class PopularService {
    static final int LIMIT = 20;
    private static final int MAX_CACHED_COMBINATIONS = 500;
    private static final Set<String> COUNTRIES = Arrays.stream(Locale.getISOCountries())
            .collect(Collectors.toUnmodifiableSet());

    private final TmdbClient tmdb;
    private final Cache<String, PopularResponse> cache;

    @Autowired
    public PopularService(TmdbClient tmdb,
                          @Value("${recomate.popular.cache-minutes:10}") long cacheMinutes) {
        this(tmdb, Duration.ofMinutes(cacheMinutes), Ticker.systemTicker());
    }

    PopularService(TmdbClient tmdb, Duration ttl, Ticker ticker) {
        this.tmdb = tmdb;
        // 국가는 ISO 코드로 검증하므로 조합 수가 제한된다.
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl)
                .maximumSize(MAX_CACHED_COMBINATIONS).ticker(ticker).build();
    }

    public PopularResponse popular(String rawGenre, String rawCountry) {
        Genre genre = parseGenre(rawGenre);
        String country = parseCountry(rawCountry);
        String key = (genre == null ? "ALL" : genre.name()) + "|" + (country == null ? "ALL" : country);
        // 불러오다 예외(502 등)가 나면 값이 저장되지 않아 다음 요청이 다시 시도한다.
        return cache.get(key, ignored -> load(genre, country));
    }

    void clearCache() {
        cache.invalidateAll();
    }

    private PopularResponse load(Genre genre, String country) {
        List<TmdbContent> found = new ArrayList<>();
        for (ContentType type : ContentType.values()) {
            // TV 매핑이 없는 장르(로맨스·공포)는 영화만 조회한다.
            if (genre != null && TmdbGenreMapper.idsFor(type, List.of(genre)).isEmpty()) continue;
            found.addAll(tmdb.discoverPopular(type, genre, country));
        }
        List<PopularResponse.Item> items = found.stream()
                .sorted(Comparator.comparingDouble(TmdbContent::popularity).reversed())
                .limit(LIMIT).map(PopularResponse.Item::from).toList();
        return new PopularResponse(genre == null ? null : genre.name(), country, items);
    }

    private static Genre parseGenre(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        if (!Genre.isValidCode(value)) throw ApiException.validation("지원하지 않는 장르입니다.");
        return Genre.valueOf(value);
    }

    private static String parseCountry(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        if (!COUNTRIES.contains(value)) {
            throw ApiException.validation("국가는 KR, US처럼 두 글자 국가 코드로 입력해주세요.");
        }
        return value;
    }

    /** 앞뒤 공백을 지우고 대문자로 바꾼다. 비어 있으면 필터 없음(null)이다. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }
}
