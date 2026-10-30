package com.recomate.recommendation.tmdb;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Set;

public record TmdbContent(long id, ContentType type, String title, String posterPath, Integer releaseYear,
                          double voteAverage, int voteCount, double popularity, Set<Genre> genres) {
}
