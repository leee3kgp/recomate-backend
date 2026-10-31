package com.recomate.session.service;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

/** Port for TMDB lookup and scoring (#13). Implementations produce results and never store them. */
public interface RecommendationGenerator {
    Result generate(RecommendationSession session);

    record Result(List<Recommendation> recommendations, SessionAnalysis analysis) {
        public Result { recommendations = List.copyOf(recommendations); }
    }
}
