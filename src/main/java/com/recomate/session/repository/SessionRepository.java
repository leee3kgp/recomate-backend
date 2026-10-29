package com.recomate.session.repository;

import com.recomate.session.domain.RecommendationSession;

public interface SessionRepository {
    void save(RecommendationSession session);
}
