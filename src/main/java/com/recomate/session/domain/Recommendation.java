package com.recomate.session.domain;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** TOP 3 추천 항목. 추천 생성 API 구현 시 필드를 확정/보완한다. */
@DynamoDbBean
public class Recommendation {
    private Integer rank;
    private Long tmdbId;
    private String mediaType; // movie | tv (RecoMate API contract)
    private String title;
    private String posterPath;
    private Double score;

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Long getTmdbId() { return tmdbId; }
    public void setTmdbId(Long tmdbId) { this.tmdbId = tmdbId; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPosterPath() { return posterPath; }
    public void setPosterPath(String posterPath) { this.posterPath = posterPath; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
