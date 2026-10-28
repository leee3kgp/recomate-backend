package com.recomate.session.domain;

import java.util.Arrays;

/** 장르 마스터 v1 (프론트 genres.js와 동일한 코드). */
public enum Genre {
    ACTION, COMEDY, ROMANCE, DRAMA, SF, FANTASY, HORROR, CRIME, MYSTERY, ANIMATION;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(g -> g.name().equals(code));
    }
}
