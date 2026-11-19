package com.recomate.recommendation.tmdb;

import com.recomate.recommendation.ContentType;
import java.util.List;

/**
 * 작품 상세 화면용 TMDB 데이터. 목록용 {@link TmdbContent}와 달리 줄거리·출연진·OTT 제공처를 담는다.
 * 비어 있을 수 있는 값은 null이며, 목록은 비어 있어도 null이 아니다.
 */
public record TmdbContentDetail(long id, ContentType type, String title, String originalTitle, String overview,
                                String posterPath, String backdropPath, String releaseDate, Integer releaseYear,
                                double voteAverage, int voteCount, List<String> genres, Integer runtime,
                                Integer seasons, Integer episodes, List<String> creators, List<Cast> cast,
                                List<Provider> providers, List<String> countries) {
    public TmdbContentDetail {
        genres = List.copyOf(genres);
        creators = List.copyOf(creators);
        cast = List.copyOf(cast);
        providers = List.copyOf(providers);
        countries = List.copyOf(countries);
    }

    /** 주요 출연진 한 명. character는 배역 이름이다. */
    public record Cast(String name, String character, String profilePath) { }

    /** 한국(KR) 정액제 스트리밍 제공처. id는 TMDB provider_id다. */
    public record Provider(int id, String name, String logoPath) { }
}
