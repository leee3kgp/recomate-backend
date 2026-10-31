package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.common.InvalidRequestException;
import com.recomate.genre.Genre;
import com.recomate.session.domain.Participant;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.ParticipantRequest;
import com.recomate.session.repository.SessionRepository;
import com.recomate.user.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    // Fixed millisecond precision keeps new GSI string keys in chronological order and matches the JS date format.
    private static final DateTimeFormatter CREATED_AT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final SessionRepository repository;
    private final UserService users;
    private final Clock clock;

    public SessionService(SessionRepository repository, UserService users, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.clock = clock;
    }

    public RecommendationSession createSession(String userId, CreateSessionRequest request) {
        validate(request);
        // Initialize the profile first; the repository atomically rechecks ACTIVE when writing.
        users.profile(userId);
        var participants = request.participants().stream().sorted(Comparator.comparing(ParticipantRequest::index))
                .map(p -> new Participant(p.index(), p.genres().stream().distinct().toList()))
                .collect(Collectors.toCollection(ArrayList::new));
        var session = new RecommendationSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setParticipantCount(request.participantCount());
        session.setParticipants(participants);
        session.setRecommendations(new ArrayList<>());
        session.setCreatedAt(CREATED_AT.format(Instant.now(clock)));
        repository.save(session);
        return session;
    }

    public void deleteSession(String userId, String rawId) {
        if (!repository.deleteOwned(userId, SessionIds.parse(rawId))) throw ApiException.notFound();
    }

    private void validate(CreateSessionRequest request) {
        if (request == null || request.participantCount() == null || request.participantCount() < 2
                || request.participantCount() > 6 || request.participants() == null) {
            throw new InvalidRequestException("참여 인원은 2~6명이어야 합니다.");
        }
        int count = request.participantCount();
        if (request.participants().size() != count) {
            throw new InvalidRequestException("participantCount와 participants 개수가 일치하지 않습니다.");
        }
        Set<Integer> indexes = new HashSet<>();
        for (ParticipantRequest participant : request.participants()) {
            if (participant == null || participant.index() == null || participant.index() < 1
                    || participant.index() > count || !indexes.add(participant.index())) {
                throw new InvalidRequestException("참여자 index는 1부터 participantCount까지 중복 없이 지정해야 합니다.");
            }
            if (participant.genres() == null || participant.genres().isEmpty()
                    || participant.genres().stream().anyMatch(code -> !Genre.isValidCode(code))) {
                throw new InvalidRequestException("참여자별로 유효한 장르를 한 개 이상 선택해주세요.");
            }
        }
    }
}
