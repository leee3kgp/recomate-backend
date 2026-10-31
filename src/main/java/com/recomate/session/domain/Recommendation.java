package com.recomate.session.domain;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Stored TOP 3 item. Optional enrichment is returned when present; reads never call TMDB. */
@DynamoDbBean
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Recommendation {
    private Integer rank;
    private Long tmdbId;
    private String mediaType; // movie | tv (RecoMate API contract)
    private String title;
    private String posterPath;
    private Double score;
    private Integer releaseYear;
    private Double voteAverage;
    private List<String> genres;
    private List<Integer> matchedParticipants;

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
    public Integer getReleaseYear() { return releaseYear; }
    public void setReleaseYear(Integer releaseYear) { this.releaseYear = releaseYear; }
    public Double getVoteAverage() { return voteAverage; }
    public void setVoteAverage(Double voteAverage) { this.voteAverage = voteAverage; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
    public List<Integer> getMatchedParticipants() { return matchedParticipants; }
    public void setMatchedParticipants(List<Integer> matchedParticipants) { this.matchedParticipants = matchedParticipants; }
}
