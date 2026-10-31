package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.session.dto.SessionDetailResponse;
import com.recomate.session.dto.SessionListResponse;
import com.recomate.session.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class SessionQueryService {
    private final SessionRepository sessions;

    public SessionQueryService(SessionRepository sessions) { this.sessions = sessions; }

    public SessionListResponse list(String userId, MultiValueMap<String, String> query) {
        if (query.keySet().stream().anyMatch(key -> !key.equals("limit") && !key.equals("nextToken"))
                || query.values().stream().anyMatch(values -> values.size() != 1)) {
            throw ApiException.validation("조회 파라미터를 확인해주세요.");
        }
        String rawLimit = query.getFirst("limit");
        int limit = 20;
        if (rawLimit != null) {
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw ApiException.validation("limit은 1~100의 정수여야 합니다.");
            limit = Integer.parseInt(rawLimit);
            if (limit > 100) throw ApiException.validation("limit은 1~100의 정수여야 합니다.");
        }
        var page = sessions.findByUser(userId, limit, SessionPageToken.decode(userId, query.getFirst("nextToken")));
        return new SessionListResponse(page.sessions().stream().map(SessionListResponse.Summary::from).toList(),
                SessionPageToken.encode(userId, page.nextCursor()));
    }

    public SessionDetailResponse detail(String userId, String rawId, MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw ApiException.validation("상세 조회에는 쿼리 파라미터를 사용할 수 없습니다.");
        var session = sessions.findById(SessionIds.parse(rawId)).filter(value -> userId.equals(value.getUserId()))
                .orElseThrow(ApiException::notFound);
        return SessionDetailResponse.from(session);
    }
}
