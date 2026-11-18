package com.recomate.content;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 상세. 로그인(JWT)이 필요하며 SecurityConfig의 기본 인증 규칙을 따른다.
 * 비회원은 프론트에서 로그인 안내를 보여주고 이 API를 호출하지 않는다.
 */
@RestController
@RequestMapping("/api/contents")
public class ContentDetailController {
    private final ContentDetailService service;

    public ContentDetailController(ContentDetailService service) {
        this.service = service;
    }

    @GetMapping("/{mediaType}/{tmdbId}")
    ResponseEntity<ContentDetailResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaType,
                                                 @PathVariable String tmdbId) {
        // watched differs per user, so shared caches must not keep the response.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.detail(jwt.getSubject(), mediaType, tmdbId));
    }
}
