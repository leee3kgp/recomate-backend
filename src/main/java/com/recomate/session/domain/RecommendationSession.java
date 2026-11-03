package com.recomate.session.domain;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * 추천 세션 1건 = DynamoDB 아이템 1개.
 * PK: sessionId / GSI(userId-createdAt-index): userId + createdAt (내 세션 목록 조회용)
 */
@DynamoDbBean
public class RecommendationSession {
    public static final String USER_INDEX = "userId-createdAt-index";

    private String sessionId;
    private String userId; // Cognito JWT sub
    private Integer participantCount;
    private List<Participant> participants = new ArrayList<>();
    private List<Recommendation> recommendations = new ArrayList<>();
    private String createdAt; // ISO-8601 (UTC)
    private SessionAnalysis analysis;
    // #18 exclusions: internal state, never returned by the API. Null until written so new items keep their shape.
    private List<Recommendation> candidates; // unranked next-best titles in score order
    private List<String> excluded; // "mediaType:tmdbId"
    private Long version; // 1 once generation stores a title, +1 per exclusion; guards regeneration and concurrent exclusions

    @DynamoDbPartitionKey
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @DynamoDbSecondaryPartitionKey(indexNames = USER_INDEX)
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Integer getParticipantCount() { return participantCount; }
    public void setParticipantCount(Integer participantCount) { this.participantCount = participantCount; }

    public List<Participant> getParticipants() { return participants; }
    public void setParticipants(List<Participant> participants) { this.participants = participants; }

    public List<Recommendation> getRecommendations() { return recommendations; }
    public void setRecommendations(List<Recommendation> recommendations) { this.recommendations = recommendations; }

    @DynamoDbSecondarySortKey(indexNames = USER_INDEX)
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public SessionAnalysis getAnalysis() { return analysis; }
    public void setAnalysis(SessionAnalysis analysis) { this.analysis = analysis; }

    public List<Recommendation> getCandidates() { return candidates; }
    public void setCandidates(List<Recommendation> candidates) { this.candidates = candidates; }
    public List<String> getExcluded() { return excluded; }
    public void setExcluded(List<String> excluded) { this.excluded = excluded; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public static String contentKey(String mediaType, Long tmdbId) { return mediaType + ":" + tmdbId; }
}
