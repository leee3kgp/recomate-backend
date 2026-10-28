package com.recomate.session.service;

import com.recomate.common.InvalidRequestException;
import com.recomate.session.domain.Genre;
import com.recomate.session.domain.Participant;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.ParticipantRequest;
import com.recomate.session.repository.SessionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final SessionRepository repository;

    public SessionService(SessionRepository repository) {
        this.repository = repository;
    }

    public RecommendationSession createSession(String userId, CreateSessionRequest request) {
        validate(request);

        List<Participant> participants = request.participants().stream()
                .sorted(Comparator.comparing(ParticipantRequest::index))
                .map(p -> new Participant(p.index(), p.genres().stream().distinct().toList()))
                .collect(Collectors.toCollection(ArrayList::new));

        RecommendationSession session = new RecommendationSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setParticipantCount(request.participantCount());
        session.setParticipants(participants);
        session.setRecommendations(new ArrayList<>());
        session.setCreatedAt(Instant.now().toString());

        repository.save(session);
        return session;
    }

    private void validate(CreateSessionRequest request) {
        int count = request.participantCount();
        if (request.participants().size() != count) {
            throw new InvalidRequestException("participantCount와 participants 개수가 일치하지 않습니다.");
        }
        Set<Integer> indexes = new HashSet<>();
        for (ParticipantRequest p : request.participants()) {
            if (p.index() < 1 || p.index() > count || !indexes.add(p.index())) {
                throw new InvalidRequestException("참여자 index는 1부터 participantCount까지 중복 없이 지정해야 합니다.");
            }
            for (String code : p.genres()) {
                if (!Genre.isValid(code)) {
                    throw new InvalidRequestException("지원하지 않는 장르 코드입니다: " + code);
                }
            }
        }
    }
}
