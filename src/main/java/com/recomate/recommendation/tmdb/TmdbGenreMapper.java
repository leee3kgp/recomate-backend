package com.recomate.recommendation.tmdb;

import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 내부 장르 코드 10개를 TMDB 영화/TV 장르 ID로 매핑한다. TV 매핑이 없는 장르는 영화만 조회한다. */
public final class TmdbGenreMapper {
    private record Ids(Integer movie, Integer tv) {}

    private static final Map<Genre, Ids> IDS = new EnumMap<>(Genre.class);
    static {
        IDS.put(Genre.ACTION, new Ids(28, 10759));    // TV: Action & Adventure
        IDS.put(Genre.COMEDY, new Ids(35, 35));
        IDS.put(Genre.ROMANCE, new Ids(10749, null)); // TV 매핑 없음
        IDS.put(Genre.DRAMA, new Ids(18, 18));
        IDS.put(Genre.SF, new Ids(878, 10765));       // TV: Sci-Fi & Fantasy
        IDS.put(Genre.FANTASY, new Ids(14, 10765));   // TV: Sci-Fi & Fantasy
        IDS.put(Genre.HORROR, new Ids(27, null));     // TV 매핑 없음
        IDS.put(Genre.CRIME, new Ids(80, 80));
        IDS.put(Genre.MYSTERY, new Ids(9648, 9648));
        IDS.put(Genre.ANIMATION, new Ids(16, 16));
    }

    private TmdbGenreMapper() {}

    private static Integer id(Genre genre, ContentType type) {
        Ids ids = IDS.get(genre);
        return type == ContentType.MOVIE ? ids.movie() : ids.tv();
    }

    /** discover 조회에 쓸 TMDB 장르 ID (중복 제거, 매핑 없는 장르는 제외). */
    public static List<Integer> idsFor(ContentType type, Collection<Genre> genres) {
        return genres.stream().map(g -> id(g, type)).filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** TMDB 결과의 장르 ID를 내부 장르로 되돌린다 (TV 10765는 SF와 FANTASY 둘 다). */
    public static Set<Genre> toInternal(ContentType type, Collection<Integer> tmdbIds) {
        Set<Genre> result = EnumSet.noneOf(Genre.class);
        for (Genre genre : Genre.all()) {
            Integer id = id(genre, type);
            if (id != null && tmdbIds.contains(id)) result.add(genre);
        }
        return result;
    }
}
