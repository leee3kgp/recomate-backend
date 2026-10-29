package com.recomate.session.controller;

import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.CreateSessionRequest;
import com.recomate.session.dto.SessionResponse;
import com.recomate.session.service.SessionService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    /** 추천 세션 생성. userId는 JWT sub에서 가져온다. */
    @PostMapping
    public ResponseEntity<SessionResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateSessionRequest request) {
        RecommendationSession session = service.createSession(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/sessions/" + session.getSessionId()))
                .body(SessionResponse.from(session));
    }
}
