package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.session.dto.RecommendationResponse;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.SessionRepository;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final SessionRepository sessions;
    private final RecommendationRepository results;
    private final RecommendationGenerator generator;

    public RecommendationService(SessionRepository sessions, RecommendationRepository results,
            RecommendationGenerator generator) {
        this.sessions = sessions;
        this.results = results;
        this.generator = generator;
    }

    public RecommendationResponse create(String userId, String rawId) {
        String sessionId = SessionIds.parse(rawId);
        var session = sessions.findById(sessionId).filter(value -> userId.equals(value.getUserId()))
                .orElseThrow(ApiException::notFound);
        // Avoid paid external calls for a known duplicate; the conditional write still settles races.
        if (session.getRecommendations() != null && !session.getRecommendations().isEmpty()) {
            throw ApiException.recommendationExists();
        }
        var generated = generator.generate(session);
        results.saveRecommendations(userId, sessionId, generated.recommendations(), generated.analysis());
        return new RecommendationResponse(sessionId, generated.recommendations(), generated.analysis());
    }
}
