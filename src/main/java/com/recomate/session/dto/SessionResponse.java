package com.recomate.session.dto;

import com.recomate.session.domain.Participant;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import java.util.List;

public record SessionResponse(
        String sessionId,
        String userId,
        int participantCount,
        List<Participant> participants,
        List<Recommendation> recommendations,
        String createdAt) {

    public static SessionResponse from(RecommendationSession s) {
        return new SessionResponse(s.getSessionId(), s.getUserId(), s.getParticipantCount(),
                s.getParticipants(), s.getRecommendations(), s.getCreatedAt());
    }
}
