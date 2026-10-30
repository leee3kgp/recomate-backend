package com.recomate.recommendation;

import com.recomate.genre.Genre;
import com.recomate.recommendation.RecommendationScorer.Scored;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.session.domain.Participant;
import com.recomate.session.domain.Recommendation;
import com.recomate.session.domain.RecommendationSession;
import com.recomate.session.domain.SessionAnalysis;
import com.recomate.session.service.RecommendationGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Joins TMDB candidates and scoring into the stored TOP 3 and analysis shape. */
@Component
public class TmdbRecommendationGenerator implements RecommendationGenerator {
    private static final int LIMIT = 3;
    private final TmdbClient tmdb;
    private final RecommendationScorer scorer;

    public TmdbRecommendationGenerator(TmdbClient tmdb, RecommendationScorer scorer) {
        this.tmdb = tmdb;
        this.scorer = scorer;
    }

    @Override
    public Result generate(RecommendationSession session) {
        Map<Integer, Set<Genre>> participants = participants(session);
        Set<Genre> genres = EnumSet.noneOf(Genre.class);
        participants.values().forEach(genres::addAll);
        List<TmdbContent> candidates = Stream.of(ContentType.values())
                .flatMap(type -> tmdb.discover(type, genres).stream()).toList();
        List<Scored> top = scorer.top(participants, candidates, LIMIT);
        List<Recommendation> recommendations = IntStream.range(0, top.size())
                .mapToObj(index -> recommendation(index + 1, top.get(index))).toList();
        return new Result(recommendations, analysis(participants, top));
    }

    private static Map<Integer, Set<Genre>> participants(RecommendationSession session) {
        Map<Integer, Set<Genre>> result = new LinkedHashMap<>();
        if (session.getParticipants() == null) return result;
        for (Participant participant : session.getParticipants()) {
            Set<Genre> genres = EnumSet.noneOf(Genre.class);
            if (participant.getGenres() != null) {
                participant.getGenres().stream().filter(Genre::isValidCode).map(Genre::valueOf).forEach(genres::add);
            }
            result.put(participant.getIndex(), genres);
        }
        return result;
    }

    private static Recommendation recommendation(int rank, Scored scored) {
        TmdbContent content = scored.content();
        var value = new Recommendation();
        value.setRank(rank);
        value.setTmdbId(content.id());
        value.setMediaType(content.type().value());
        value.setTitle(content.title());
        value.setPosterPath(content.posterPath());
        value.setReleaseYear(content.releaseYear());
        value.setVoteAverage(content.voteAverage());
        value.setGenres(content.genres().stream().sorted().map(Genre::name).toList());
        value.setScore(scored.score());
        value.setMatchedParticipants(scored.matchedParticipants());
        return value;
    }

    private static SessionAnalysis analysis(Map<Integer, Set<Genre>> participants, List<Scored> top) {
        var analysis = new SessionAnalysis();
        analysis.setGenreScores(genreScores(participants.values()));
        List<SessionAnalysis.ParticipantMatch> matches = new ArrayList<>();
        participants.keySet().forEach(index -> matches.add(participantMatch(index, top)));
        analysis.setParticipantMatch(matches);
        return analysis;
    }

    private static List<SessionAnalysis.GenreScore> genreScores(Collection<Set<Genre>> selections) {
        return Genre.all().stream().map(genre -> {
            var score = new SessionAnalysis.GenreScore();
            score.setGenre(genre.name());
            score.setCount((int) selections.stream().filter(selected -> selected.contains(genre)).count());
            return score;
        }).filter(score -> score.getCount() > 0)
                .sorted(Comparator.comparingInt(SessionAnalysis.GenreScore::getCount).reversed()).toList();
    }

    /** Match rate is the share of the recommended titles that fit this participant. */
    private static SessionAnalysis.ParticipantMatch participantMatch(int index, List<Scored> top) {
        long matched = top.stream().filter(scored -> scored.matchedParticipants().contains(index)).count();
        var match = new SessionAnalysis.ParticipantMatch();
        match.setIndex(index);
        match.setMatchRate(top.isEmpty() ? 0 : Math.round(100.0 * matched / top.size()) / 100.0);
        return match;
    }
}
