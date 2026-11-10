package com.recomate.popular;

import com.recomate.recommendation.tmdb.TmdbContent;
import java.util.List;

/** 인기 목록 응답. 적용한 필터를 되돌려 주며 필터가 없으면 genre·country는 null이다. */
public record PopularResponse(String genre, String country, List<Item> items) {

    public record Item(long id, String type, String title, String posterPath,
                       Integer releaseYear, double voteAverage) {
        static Item from(TmdbContent content) {
            return new Item(content.id(), content.type().value(), content.title(), content.posterPath(),
                    content.releaseYear(), content.voteAverage());
        }
    }
}
