package com.recomate.recommendation.tmdb;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Collection;
import java.util.List;

public interface TmdbClient {
    /** 주어진 내부 장르에 해당하는 인기순 후보 콘텐츠를 조회한다. */
    List<TmdbContent> discover(ContentType type, Collection<Genre> genres);
}