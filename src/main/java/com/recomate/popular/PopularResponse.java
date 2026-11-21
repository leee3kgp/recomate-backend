package com.recomate.popular;

import com.recomate.recommendation.tmdb.TmdbContent;
import java.util.List;

/**
 * 인기 목록 응답. 적용한 필터를 되돌려 주며 필터가 없으면 genre·country·type은 null이다.
 * sort는 생략해도 기본값(popular)이 들어간다.
 */
public record PopularResponse(String genre, String country, String type, String sort, List<Item> items) {

    public record Item(long id, String type, String title, String posterPath,
                       Integer releaseYear, double voteAverage) {
        static Item from(TmdbContent content) {
            return new Item(content.id(), content.type().value(), content.title(), content.posterPath(),
                    content.releaseYear(), content.voteAverage());
        }
    }
}
