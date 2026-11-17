package com.recomate.watched;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/watched")
public class WatchedController {
    private final WatchedService watched;
    public WatchedController(WatchedService watched) { this.watched = watched; }

    @GetMapping
    ResponseEntity<WatchedService.WatchedListResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(watched.list(jwt.getSubject(), query));
    }

    @PostMapping
    ResponseEntity<WatchedItem> add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WatchedRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(watched.add(jwt.getSubject(), request));
    }

    @DeleteMapping("/{mediaType}/{tmdbId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaType,
            @PathVariable String tmdbId) {
        watched.remove(jwt.getSubject(), mediaType, tmdbId);
        return ResponseEntity.noContent().build();
    }
}
