package com.recomate.session.repository;

import com.recomate.session.domain.RecommendationSession;
import java.util.List;
import java.util.Optional;

public interface SessionRepository {
    void save(RecommendationSession session);
    Optional<RecommendationSession> findById(String sessionId);
    Page findByUser(String userId, int limit, Cursor cursor);
    /** Returns false when the session is absent or owned by someone else. */
    boolean deleteOwned(String userId, String sessionId);

    record Cursor(String sessionId, String createdAt) { }
    record Page(List<RecommendationSession> sessions, Cursor nextCursor) {
        public Page { sessions = List.copyOf(sessions); }
    }
}
