package com.recomate.recommendation;

import com.recomate.genre.Genre;
import com.recomate.recommendation.tmdb.TmdbContent;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 점수 = 100 × (0.6×그룹 장르 일치도 + 0.25×평점 + 0.15×인기도). 일치하는 참여자가 없으면 제외한다.
 * 그룹 장르 일치도 = (장르가 겹치는 참여자 비율 + 참여자별 고른 장르 충족률 평균) ÷ 2.
 * 앞 항은 더 많은 참여자를 챙기고, 뒤 항은 고른 장르를 더 많이 담은 작품을 앞세운다.
 */
@Component
public class RecommendationScorer {
    static final double W_GENRE = 0.6;
    static final double W_RATING = 0.25;
    static final double W_POPULARITY = 0.15;

    public record Scored(TmdbContent content, double score, List<Integer> matchedParticipants) {}

    /** participants: 참여자 index → 선택한 내부 장르 */
    public List<Scored> top(Map<Integer, Set<Genre>> participants, Collection<TmdbContent> candidates, int limit) {
        if (participants.isEmpty()) return List.of();
        double maxPopularity = candidates.stream().mapToDouble(TmdbContent::popularity).max().orElse(0);
        Set<String> seen = new HashSet<>();
        return candidates.stream()
                .filter(c -> seen.add(c.type().value() + ":" + c.id()))
                .map(c -> score(participants, c, maxPopularity))
                .filter(s -> !s.matchedParticipants().isEmpty())
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Comparator.comparingDouble((Scored s) -> s.content().voteAverage()).reversed())
                        .thenComparingLong(s -> s.content().id()))
                .limit(limit)
                .toList();
    }

    private Scored score(Map<Integer, Set<Genre>> participants, TmdbContent content, double maxPopularity) {
        List<Integer> matched = participants.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(content.genres()::contains))
                .map(Map.Entry::getKey).sorted().toList();
        double covered = (double) matched.size() / participants.size();
        double depth = participants.values().stream()
                .mapToDouble(selected -> selectedShare(selected, content.genres())).average().orElse(0);
        double genreMatch = (covered + depth) / 2;
        double rating = Math.min(content.voteAverage(), 10) / 10;
        double popularity = maxPopularity > 0 ? Math.log1p(content.popularity()) / Math.log1p(maxPopularity) : 0;
        double score = 100 * (W_GENRE * genreMatch + W_RATING * rating + W_POPULARITY * popularity);
        return new Scored(content, Math.round(score * 10) / 10.0, matched);
    }

    private static double selectedShare(Set<Genre> selected, Set<Genre> genres) {
        if (selected.isEmpty()) return 0;
        return (double) selected.stream().filter(genres::contains).count() / selected.size();
    }
}
