package com.recomate.recommendation.tmdb;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Set;

/** releaseDate는 영화 개봉일·TV 첫 방영일(yyyy-MM-dd)이며, 없으면 null이다. */
public record TmdbContent(long id, ContentType type, String title, String posterPath, Integer releaseYear,
                          double voteAverage, int voteCount, double popularity, Set<Genre> genres,
                          String releaseDate) {

    /** 날짜가 필요 없는 기존 호출(추천·테스트)용 */
    public TmdbContent(long id, ContentType type, String title, String posterPath, Integer releaseYear,
                       double voteAverage, int voteCount, double popularity, Set<Genre> genres) {
        this(id, type, title, posterPath, releaseYear, voteAverage, voteCount, popularity, genres, null);
    }
}
