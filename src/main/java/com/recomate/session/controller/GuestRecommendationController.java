package com.recomate.session.controller;

import com.recomate.session.dto.GuestRecommendationRequest;
import com.recomate.session.dto.GuestRecommendationResponse;
import com.recomate.session.service.GuestRecommendationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint (no login): returns a TOP 3 for the given participants and stores nothing. */
@RestController
@RequestMapping("/api/guest/recommendations")
public class GuestRecommendationController {
    private final GuestRecommendationService service;

    public GuestRecommendationController(GuestRecommendationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<GuestRecommendationResponse> recommend(@Valid @RequestBody GuestRecommendationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recommend(request));
    }
}
