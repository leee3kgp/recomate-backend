package com.recomate.session.dto;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

public record RecommendationResponse(String sessionId, List<Recommendation> recommendations, SessionAnalysis analysis) {
}
