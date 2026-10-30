package com.recomate.session.dto;

import com.recomate.session.domain.RecommendationSession;
import java.util.List;

public record SessionListResponse(List<Summary> sessions, String nextToken) {
    public SessionListResponse { sessions = List.copyOf(sessions); }

    public record Summary(String sessionId, int participantCount, String createdAt) {
        public static Summary from(RecommendationSession session) {
            return new Summary(session.getSessionId(), session.getParticipantCount(), session.getCreatedAt());
        }
    }
}
