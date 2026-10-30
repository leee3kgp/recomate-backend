package com.recomate.session.repository;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

/** Shared persistence boundary for the recommendation API; no TMDB/scoring logic here. */
public interface RecommendationRepository {
    default void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations) {
        saveRecommendations(userId, sessionId, recommendations, null);
    }

    /** Stores results and optional analysis in the session item, so reads and withdrawal need no extra step. */
    void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations, SessionAnalysis analysis);
}
