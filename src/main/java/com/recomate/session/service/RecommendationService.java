package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.session.dto.RecommendationResponse;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.SessionRepository;
import com.recomate.user.UserService;
import com.recomate.watched.WatchedRepository;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final SessionRepository sessions;
    private final RecommendationRepository results;
    private final RecommendationGenerator generator;
    private final UserService users;
    private final WatchedRepository watched;

    public RecommendationService(SessionRepository sessions, RecommendationRepository results,
            RecommendationGenerator generator, UserService users, WatchedRepository watched) {
        this.sessions = sessions;
        this.results = results;
        this.generator = generator;
        this.users = users;
        this.watched = watched;
    }

    public RecommendationResponse create(String userId, String rawId) {
        String sessionId = SessionIds.parse(rawId);
        var session = sessions.findById(sessionId).filter(value -> userId.equals(value.getUserId()))
                .orElseThrow(ApiException::notFound);
        // Avoid paid external calls for a known duplicate; the conditional write still settles races.
        // version is set once generation stores a title, so excluding every title does not reopen generation.
        if (session.getVersion() != null
                || session.getRecommendations() != null && !session.getRecommendations().isEmpty()) {
            throw ApiException.recommendationExists();
        }
        Set<String> excluded = users.excludeWatched(userId) ? watched.keys(userId) : Set.of();
        var generated = generator.generate(session, excluded);
        results.saveRecommendations(userId, sessionId, generated.recommendations(), generated.analysis(),
                generated.candidates());
        return new RecommendationResponse(sessionId, generated.recommendations(), generated.analysis());
    }
}
