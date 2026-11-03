package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.dto.ExclusionRequest;
import com.recomate.session.dto.RecommendationResponse;
import com.recomate.session.repository.RecommendationRepository;
import com.recomate.session.repository.RecommendationRepository.Exclusion;
import com.recomate.session.repository.SessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * "이미 봤어요" (#18): drops one current title and promotes the best stored candidate.
 * Never calls TMDB; candidates are stored at generation time in score order.
 */
@Service
public class ExclusionService {
    /** One retry absorbs a concurrent exclusion of another title in the same session. */
    private static final int ATTEMPTS = 2;
    private final SessionRepository sessions;
    private final RecommendationRepository results;
    private final RecommendationGenerator generator;

    public ExclusionService(SessionRepository sessions, RecommendationRepository results,
            RecommendationGenerator generator) {
        this.sessions = sessions;
        this.results = results;
        this.generator = generator;
    }

    public RecommendationResponse exclude(String userId, String rawId, ExclusionRequest request) {
        String sessionId = SessionIds.parse(rawId);
        String target = RecommendationSession.contentKey(request.mediaType(), request.tmdbId());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var session = sessions.findById(sessionId).filter(value -> userId.equals(value.getUserId()))
                    .orElseThrow(ApiException::notFound);
            List<Recommendation> current = orEmpty(session.getRecommendations());
            List<String> excluded = orEmpty(session.getExcluded());
            // Repeated taps return the current state instead of failing.
            if (excluded.contains(target)) return response(sessionId, current, session);
            if (current.stream().noneMatch(item -> key(item).equals(target))) {
                throw ApiException.validation("현재 추천 결과에 없는 콘텐츠입니다.");
            }

            List<Recommendation> remaining = new ArrayList<>(current.stream()
                    .filter(item -> !key(item).equals(target)).toList());
            Set<String> taken = remaining.stream().map(ExclusionService::key).collect(Collectors.toSet());
            List<Recommendation> pool = orEmpty(session.getCandidates()).stream()
                    .filter(item -> !excluded.contains(key(item)) && !taken.contains(key(item))).toList();
            if (!pool.isEmpty()) remaining.add(pool.get(0));
            for (int rank = 0; rank < remaining.size(); rank++) remaining.get(rank).setRank(rank + 1);
            List<String> nextExcluded = new ArrayList<>(excluded);
            nextExcluded.add(target);

            var exclusion = new Exclusion(remaining, generator.analyze(session, remaining),
                    pool.isEmpty() ? List.of() : pool.subList(1, pool.size()), nextExcluded);
            if (results.saveExclusion(userId, sessionId, session.getVersion(), exclusion)) {
                return new RecommendationResponse(sessionId, exclusion.recommendations(), exclusion.analysis());
            }
        }
        throw ApiException.concurrentUpdate();
    }

    private static RecommendationResponse response(String sessionId, List<Recommendation> current,
            RecommendationSession session) {
        return new RecommendationResponse(sessionId, current, session.getAnalysis());
    }

    private static String key(Recommendation item) {
        return RecommendationSession.contentKey(item.getMediaType(), item.getTmdbId());
    }

    private static <T> List<T> orEmpty(List<T> values) { return values == null ? List.of() : values; }
}
