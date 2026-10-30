package com.recomate.session.controller;

import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.RecommendationResponse;
import com.recomate.session.dto.SessionDetailResponse;
import com.recomate.session.dto.SessionListResponse;
import com.recomate.session.dto.SessionResponse;
import com.recomate.session.service.RecommendationService;
import com.recomate.session.service.SessionQueryService;
import com.recomate.session.service.SessionService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService service;
    private final SessionQueryService queries;
    private final RecommendationService recommendations;

    public SessionController(SessionService service, SessionQueryService queries,
            RecommendationService recommendations) {
        this.service = service;
        this.queries = queries;
        this.recommendations = recommendations;
    }

    @GetMapping
    public ResponseEntity<SessionListResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(jwt.getSubject(), query));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionDetailResponse> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId, @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(jwt.getSubject(), sessionId, query));
    }

    /** 추천 세션 생성. userId는 JWT sub에서 가져온다. */
    @PostMapping
    public ResponseEntity<SessionResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateSessionRequest request) {
        RecommendationSession session = service.createSession(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/sessions/" + session.getSessionId()))
                .body(SessionResponse.from(session));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        service.deleteSession(jwt.getSubject(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/recommendations")
    public ResponseEntity<RecommendationResponse> recommend(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recommendations.create(jwt.getSubject(), sessionId));
    }
}
