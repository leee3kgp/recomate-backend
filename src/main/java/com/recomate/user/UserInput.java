package com.recomate.user;

import com.recomate.common.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UserInput {
    private static final Set<String> GENRES = Set.of("ACTION", "COMEDY", "ROMANCE", "DRAMA", "SF",
            "FANTASY", "HORROR", "CRIME", "MYSTERY", "ANIMATION");
    private UserInput() { }

    public static Object onlyField(Map<String, Object> body, String field) {
        if (body.size() != 1 || !body.containsKey(field)) {
            throw ApiException.validation(field + " 필드만 전달해주세요.");
        }
        return body.get(field);
    }

    public static String nickname(Object value) {
        if (!(value instanceof String raw)) {
            throw ApiException.validation("닉네임은 문자열이어야 합니다.");
        }
        int[] points = raw.codePoints().toArray();
        int start = 0, end = points.length;
        while (start < end && space(points[start])) start++;
        while (end > start && space(points[end - 1])) end--;
        if (end - start < 1 || end - start > 20 || raw.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.validation("닉네임은 공백만 입력할 수 없으며 1~20자여야 합니다.");
        }
        return new String(points, start, end - start);
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    public static List<String> genres(Object value) {
        if (!(value instanceof List<?> values) || values.size() > GENRES.size()) {
            throw ApiException.validation("장르는 최대 10개의 코드 배열이어야 합니다.");
        }
        Set<String> seen = new HashSet<>();
        for (Object entry : values) {
            if (!(entry instanceof String code) || !GENRES.contains(code) || !seen.add(code)) {
                throw ApiException.validation("유효한 장르 코드를 중복 없이 전달해주세요.");
            }
        }
        return values.stream().map(String.class::cast).toList();
    }
}
