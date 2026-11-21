package com.recomate.popular;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.DiscoverSort;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.recommendation.tmdb.TmdbGenreMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 장르·국가·종류 필터와 정렬별 인기 목록. 결과는 필터·정렬 조합을 키로 인스턴스 메모리에 잠시 보관한다.
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

    public PopularResponse popular(String rawGenre, String rawCountry, String rawType, String rawSort) {
        Genre genre = parseGenre(rawGenre);
        String country = parseCountry(rawCountry);
        ContentType type = parseType(rawType);
        DiscoverSort sort = parseSort(rawSort);
        // 장르|국가|종류|정렬. 생략한 값은 ALL·popular로 바꾼 뒤 만들어, 생략과 기본값 지정이 같은 키를 쓴다.
        String key = String.join("|", genre == null ? "ALL" : genre.name(), country == null ? "ALL" : country,
                type == null ? "ALL" : type.value(), sort.code());
        // 불러오다 예외(502 등)가 나면 값이 저장되지 않아 다음 요청이 다시 시도한다.
        return cache.get(key, ignored -> load(genre, country, type, sort));
    }

    void clearCache() {
        cache.invalidateAll();
    }

    private PopularResponse load(Genre genre, String country, ContentType only, DiscoverSort sort) {
        List<TmdbContent> found = new ArrayList<>();
        for (ContentType type : ContentType.values()) {
            // 종류를 지정하면 그 종류만 조회한다.
            if (only != null && type != only) continue;
            // TV 매핑이 없는 장르(로맨스·공포)는 영화만 조회한다. type=tv와 함께 오면 빈 목록이 된다.
            if (genre != null && TmdbGenreMapper.idsFor(type, List.of(genre)).isEmpty()) continue;
            found.addAll(tmdb.discoverPopular(type, genre, country, sort));
        }
        // 영화와 TV를 합칠 때도 요청한 정렬 기준으로 다시 정렬한다.
        List<PopularResponse.Item> items = found.stream()
                .sorted(sort.order())
                .limit(LIMIT).map(PopularResponse.Item::from).toList();
        return new PopularResponse(genre == null ? null : genre.name(), country,
                only == null ? null : only.value(), sort.code(), items);
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

    private static ContentType parseType(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        for (ContentType type : ContentType.values()) {
            if (type.name().equals(value)) return type;
        }
        throw ApiException.validation("종류는 movie 또는 tv로 입력해주세요.");
    }

    /** 생략하면 인기순(popular)이다. */
    private static DiscoverSort parseSort(String raw) {
        String value = normalize(raw);
        if (value == null) return DiscoverSort.POPULAR;
        for (DiscoverSort sort : DiscoverSort.values()) {
            if (sort.name().equals(value)) return sort;
        }
        throw ApiException.validation("정렬은 popular, rating, latest 중 하나로 입력해주세요.");
    }

    /** 앞뒤 공백을 지우고 대문자로 바꾼다. 비어 있으면 필터 없음(null)이다. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }
}
