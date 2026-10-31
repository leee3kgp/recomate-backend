package com.recomate.session.dto;

import com.recomate.session.domain.Participant;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

public record SessionDetailResponse(String sessionId, String userId, int participantCount,
        List<Participant> participants, List<Recommendation> recommendations, String createdAt, SessionAnalysis analysis) {
    public static SessionDetailResponse from(RecommendationSession session) {
        return new SessionDetailResponse(session.getSessionId(), session.getUserId(), session.getParticipantCount(),
                session.getParticipants() == null ? List.of() : session.getParticipants(),
                session.getRecommendations() == null ? List.of() : session.getRecommendations(),
                session.getCreatedAt(), session.getAnalysis());
    }
}
