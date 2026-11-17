package com.recomate.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recomate.recommendation.tmdb.TmdbContentDetail;
import java.util.List;

/**
 * 작품 상세 응답. TMDB에 값이 없으면 해당 필드는 빠지고, 목록 필드는 빈 배열이다.
 * runtime은 분 단위(TV는 회당 러닝타임), seasons·episodes는 TV에만 있다.
 * watched는 요청한 사용자가 이 작품을 본 작품에 저장했는지를 뜻한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentDetailResponse(long tmdbId, String mediaType, String title, String originalTitle,
                                    String overview, String posterPath, String backdropPath, String releaseDate,
                                    Integer releaseYear, double voteAverage, int voteCount, List<String> genres,
                                    Integer runtime, Integer seasons, Integer episodes, List<String> creators,
                                    List<TmdbContentDetail.Cast> cast, List<TmdbContentDetail.Provider> providers,
                                    boolean watched) {

    static ContentDetailResponse of(TmdbContentDetail detail, boolean watched) {
        return new ContentDetailResponse(detail.id(), detail.type().value(), detail.title(), detail.originalTitle(),
                detail.overview(), detail.posterPath(), detail.backdropPath(), detail.releaseDate(),
                detail.releaseYear(), detail.voteAverage(), detail.voteCount(), detail.genres(), detail.runtime(),
                detail.seasons(), detail.episodes(), detail.creators(), detail.cast(), detail.providers(), watched);
    }
}
