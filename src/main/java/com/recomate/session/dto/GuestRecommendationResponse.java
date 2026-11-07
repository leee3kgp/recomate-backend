package com.recomate.session.dto;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.SessionAnalysis;
import java.util.List;

/** Same result shape as a member session's recommendations, without a session id because nothing is saved. */
public record GuestRecommendationResponse(List<Recommendation> recommendations, SessionAnalysis analysis) {
}
