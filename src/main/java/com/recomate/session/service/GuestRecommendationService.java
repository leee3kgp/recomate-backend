package com.recomate.session.service;

import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.GuestRecommendationRequest;
import com.recomate.session.dto.GuestRecommendationResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Recommendations for visitors without an account. Uses the member generator and scoring but stores nothing:
 * the guest's "이미 봤어요" titles arrive with every request, so no user data is written.
 */
@Service
public class GuestRecommendationService {
    private static final int LIMIT = 3;
    private final RecommendationGenerator generator;

    public GuestRecommendationService(RecommendationGenerator generator) {
        this.generator = generator;
    }

    public GuestRecommendationResponse recommend(GuestRecommendationRequest request) {
        var session = new RecommendationSession();
        session.setParticipantCount(request.participantCount());
        session.setParticipants(SessionService.participants(request.session()));
        Set<String> excluded = request.excludedOrEmpty().stream()
                .map(item -> RecommendationSession.contentKey(item.mediaType(), item.tmdbId()))
                .collect(Collectors.toSet());

        var generated = generator.generate(session);
        if (excluded.isEmpty()) return new GuestRecommendationResponse(generated.recommendations(), generated.analysis());

        // Like a member exclusion: remaining titles keep their order and the best next candidates fill the gaps.
        List<Recommendation> top = Stream.concat(generated.recommendations().stream(), generated.candidates().stream())
                .filter(item -> !excluded.contains(RecommendationSession.contentKey(item.getMediaType(), item.getTmdbId())))
                .limit(LIMIT).toList();
        for (int rank = 0; rank < top.size(); rank++) top.get(rank).setRank(rank + 1);
        return new GuestRecommendationResponse(top, generator.analyze(session, top));
    }
}
