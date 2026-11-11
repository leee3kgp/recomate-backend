package com.recomate.recommendation.tmdb;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Collection;
import java.util.List;

public interface TmdbClient {
    /** 주어진 내부 장르에 해당하는 인기순 후보 콘텐츠를 조회한다. */
    List<TmdbContent> discover(ContentType type, Collection<Genre> genres);

    /**
     * 장르·국가 필터가 있는 인기순 콘텐츠를 조회한다. genre, country가 null이면 그 필터는 적용하지 않는다.
     * country는 ISO 3166-1 alpha-2 대문자 코드다. 기존 구현과 테스트 대역은 이 메서드 없이도 컴파일되도록
     * 기본 구현은 지원하지 않음을 알린다.
     */
    default List<TmdbContent> discoverPopular(ContentType type, Genre genre, String country) {
        throw new UnsupportedOperationException("Popular discovery is not supported by this client");
    }
}