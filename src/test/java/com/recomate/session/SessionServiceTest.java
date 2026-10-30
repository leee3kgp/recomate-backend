package com.recomate.session;

import static org.junit.jupiter.api.Assertions.*;

import com.recomate.common.InvalidRequestException;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.ParticipantRequest;
import com.recomate.session.service.SessionService;
import com.recomate.session.repository.SessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import com.recomate.user.UserService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.Test;

class SessionServiceTest {
    private final List<RecommendationSession> saved = new ArrayList<>();
    private final SessionRepository repository = mock(SessionRepository.class);
    private final SessionService service = new SessionService(repository, mock(UserService.class), Clock.systemUTC());

    SessionServiceTest() {
        doAnswer(invocation -> { saved.add(invocation.getArgument(0)); return null; }).when(repository).save(any());
    }

    private static ParticipantRequest p(int i, String... genres) {
        return new ParticipantRequest(i, List.of(genres));
    }

    @Test
    void creationTimestampsKeepStringOrderForCloseConcurrentInstants() {
        var input = new CreateSessionRequest(2, List.of(p(1, "SF"), p(2, "ACTION")));
        String previous = null;
        for (String instant : List.of("2026-10-30T00:00:00Z", "2026-10-30T00:00:00.100Z",
                "2026-10-30T00:00:00.123Z", "2026-10-30T00:00:00.124Z", "2026-10-30T00:00:01Z")) {
            var fixed = Clock.fixed(java.time.Instant.parse(instant), java.time.ZoneOffset.UTC);
            String timestamp = new SessionService(repository, mock(UserService.class), fixed)
                    .createSession("user-sub", input).getCreatedAt();
            assertEquals(java.time.Instant.parse(instant), java.time.Instant.parse(timestamp));
            assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
            if (previous != null) assertTrue(previous.compareTo(timestamp) < 0);
            previous = timestamp;
        }
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
