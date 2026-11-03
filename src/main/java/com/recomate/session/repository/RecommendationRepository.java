package com.recomate.session.repository;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

/** Shared persistence boundary for the recommendation API; no TMDB/scoring logic here. */
public interface RecommendationRepository {
    default void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations) {
        saveRecommendations(userId, sessionId, recommendations, null);
    }

    default void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations,
            SessionAnalysis analysis) {
        saveRecommendations(userId, sessionId, recommendations, analysis, List.of());
    }

    /** Stores results, optional analysis and next candidates in the session item, so reads and withdrawal need no extra step. */
    void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations,
            SessionAnalysis analysis, List<Recommendation> candidates);

    /**
     * Replaces results after an exclusion only if the stored version still equals expectedVersion
     * (null: never versioned). Returns false when another write won, so the caller can re-read.
     */
    boolean saveExclusion(String userId, String sessionId, Long expectedVersion, Exclusion exclusion);

    record Exclusion(List<Recommendation> recommendations, SessionAnalysis analysis,
            List<Recommendation> candidates, List<String> excluded) {
        public Exclusion {
            recommendations = List.copyOf(recommendations);
            candidates = List.copyOf(candidates);
            excluded = List.copyOf(excluded);
        }
    }
}
