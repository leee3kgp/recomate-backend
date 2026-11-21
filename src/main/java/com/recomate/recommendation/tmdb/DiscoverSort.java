package com.recomate.recommendation.tmdb;

import com.recomate.recommendation.ContentType;
import java.util.Comparator;

/** 인기 목록 정렬 기준. 요청 값(code)과 TMDB discover 정렬 조건, 영화·TV를 합칠 때의 정렬을 함께 둔다. */
public enum DiscoverSort {
    POPULAR("popular"),
    RATING("rating"),
    LATEST("latest");

    /** 기존 인기 목록·추천과 같은 평가 수 기준 */
    static final int MIN_VOTES = 200;
    /** 최신순은 막 공개된 작품이 평가 수 200을 채우기 어려워 기준을 낮춘다 */
    static final int MIN_VOTES_LATEST = 20;

    private final String code;

    DiscoverSort(String code) { this.code = code; }

    public String code() { return code; }

    /** TMDB sort_by 값 */
    String sortBy(ContentType type) {
        return switch (this) {
            case POPULAR -> "popularity.desc";
            case RATING -> "vote_average.desc";
            case LATEST -> dateField(type) + ".desc";
        };
    }

    int minVotes() {
        return this == LATEST ? MIN_VOTES_LATEST : MIN_VOTES;
    }

    /** 영화는 개봉일, TV는 첫 방영일로 TMDB 필드 이름이 다르다. */
    static String dateField(ContentType type) {
        return type == ContentType.MOVIE ? "primary_release_date" : "first_air_date";
    }

    /** 영화와 TV를 합친 목록을 같은 기준으로 다시 정렬할 때 쓴다. */
    public Comparator<TmdbContent> order() {
        Comparator<TmdbContent> byPopularity = Comparator.comparingDouble(TmdbContent::popularity).reversed();
        return switch (this) {
            case POPULAR -> byPopularity;
            case RATING -> Comparator.comparingDouble(TmdbContent::voteAverage).reversed()
                    .thenComparing(Comparator.comparingInt(TmdbContent::voteCount).reversed());
            case LATEST -> Comparator.comparing(TmdbContent::releaseDate,
                            Comparator.nullsLast(Comparator.<String>reverseOrder()))
                    .thenComparing(byPopularity);
        };
    }
}
