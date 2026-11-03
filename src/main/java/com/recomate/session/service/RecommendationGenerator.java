package com.recomate.session.service;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

/** Port for TMDB lookup and scoring (#13). Implementations produce results and never store them. */
public interface RecommendationGenerator {
    Result generate(RecommendationSession session);

    /** Recomputes analysis for stored titles without external calls, e.g. after an exclusion (#18). */
    SessionAnalysis analyze(RecommendationSession session, List<Recommendation> recommendations);

    /** candidates: next-best unranked titles in score order, stored so exclusions avoid TMDB calls. */
    record Result(List<Recommendation> recommendations, SessionAnalysis analysis, List<Recommendation> candidates) {
        public Result {
            recommendations = List.copyOf(recommendations);
            candidates = List.copyOf(candidates);
        }

        public Result(List<Recommendation> recommendations, SessionAnalysis analysis) {
            this(recommendations, analysis, List.of());
        }
    }
}
