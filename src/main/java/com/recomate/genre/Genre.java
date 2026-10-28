package com.recomate.genre;

import java.util.List;

/** RecoMate genre master v1, in the display order agreed with the frontend. */
public enum Genre {
    ACTION("액션"),
    COMEDY("코미디"),
    ROMANCE("로맨스"),
    DRAMA("드라마"),
    SF("SF"),
    FANTASY("판타지"),
    HORROR("공포"),
    CRIME("범죄"),
    MYSTERY("미스터리"),
    ANIMATION("애니메이션");

    private static final List<Genre> ALL = List.of(values());
    private final String displayName;

    Genre(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    public static List<Genre> all() { return ALL; }

    public static boolean isValidCode(String code) {
        return ALL.stream().anyMatch(genre -> genre.name().equals(code));
    }
}
