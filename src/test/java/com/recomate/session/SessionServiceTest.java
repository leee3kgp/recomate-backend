package com.recomate.session;

import static org.junit.jupiter.api.Assertions.*;

import com.recomate.common.InvalidRequestException;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.ParticipantRequest;
import com.recomate.session.service.SessionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionServiceTest {
    private final List<RecommendationSession> saved = new ArrayList<>();
    private final SessionService service = new SessionService(saved::add);

    private static ParticipantRequest p(int i, String... genres) {
        return new ParticipantRequest(i, List.of(genres));
    }

    @Test
    void createsSessionWithSubAndEmptyRecommendations() {
        var req = new CreateSessionRequest(2, List.of(p(2, "SF", "SF", "ACTION"), p(1, "DRAMA")));
        RecommendationSession s = service.createSession("user-sub", req);

        assertEquals(1, saved.size());
        assertEquals("user-sub", s.getUserId());
        assertEquals(2, s.getParticipantCount());
        assertEquals(1, s.getParticipants().get(0).getIndex()); // index 순 정렬
        assertEquals(List.of("SF", "ACTION"), s.getParticipants().get(1).getGenres()); // 중복 제거
        assertTrue(s.getRecommendations().isEmpty());
        assertNotNull(s.getSessionId());
        assertNotNull(s.getCreatedAt());
    }

    @Test
    void rejectsCountMismatch() {
        var req = new CreateSessionRequest(3, List.of(p(1, "SF"), p(2, "SF")));
        assertThrows(InvalidRequestException.class, () -> service.createSession("u", req));
        assertTrue(saved.isEmpty());
    }

    @Test
    void rejectsDuplicateOrOutOfRangeIndex() {
        assertThrows(InvalidRequestException.class,
                () -> service.createSession("u", new CreateSessionRequest(2, List.of(p(1, "SF"), p(1, "SF")))));
        assertThrows(InvalidRequestException.class,
                () -> service.createSession("u", new CreateSessionRequest(2, List.of(p(1, "SF"), p(3, "SF")))));
    }

    @Test
    void rejectsUnknownGenre() {
        var req = new CreateSessionRequest(2, List.of(p(1, "SF"), p(2, "WESTERN")));
        assertThrows(InvalidRequestException.class, () -> service.createSession("u", req));
    }
}
