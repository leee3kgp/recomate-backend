package com.recomate.session.repository;

import com.recomate.session.domain.Recommendation;
import java.util.List;

/** Shared persistence boundary for the recommendation API; no TMDB/scoring logic here. */
public interface RecommendationRepository {
    void saveRecommendations(String userId, String sessionId, List<Recommendation> recommendations);
}
