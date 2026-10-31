package com.recomate.session.domain;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** Optional stored analysis. Absence means not stored, not a computed zero score. */
@DynamoDbBean
public class SessionAnalysis {
    private List<GenreScore> genreScores = new ArrayList<>();
    private List<ParticipantMatch> participantMatch = new ArrayList<>();

    public List<GenreScore> getGenreScores() { return genreScores; }
    public void setGenreScores(List<GenreScore> genreScores) { this.genreScores = genreScores == null ? new ArrayList<>() : genreScores; }
    public List<ParticipantMatch> getParticipantMatch() { return participantMatch; }
    public void setParticipantMatch(List<ParticipantMatch> participantMatch) { this.participantMatch = participantMatch == null ? new ArrayList<>() : participantMatch; }

    @DynamoDbBean
    public static class GenreScore {
        private String genre;
        private Integer count;
        public String getGenre() { return genre; }
        public void setGenre(String genre) { this.genre = genre; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    @DynamoDbBean
    public static class ParticipantMatch {
        private Integer index;
        private Double matchRate;
        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }
        public Double getMatchRate() { return matchRate; }
        public void setMatchRate(Double matchRate) { this.matchRate = matchRate; }
    }
}
