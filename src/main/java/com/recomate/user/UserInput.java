package com.recomate.user;

import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UserInput {
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

    /** 참여자 이름(선택): 앞뒤 공백 제거 후 1~10자. 비었거나 공백뿐이면 null. */
    public static String participantName(String raw) {
        if (raw == null) return null;
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.validation("참여자 이름에 사용할 수 없는 문자가 있습니다.");
        }
        int[] points = raw.codePoints().toArray();
        int start = 0, end = points.length;
        while (start < end && space(points[start])) start++;
        while (end > start && space(points[end - 1])) end--;
        if (end == start) return null;
        if (end - start > 10) throw ApiException.validation("참여자 이름은 10자 이내로 입력해주세요.");
        return new String(points, start, end - start);
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    public static List<String> genres(Object value) {
        if (!(value instanceof List<?> values) || values.size() > Genre.all().size()) {
            throw ApiException.validation("장르는 최대 10개의 코드 배열이어야 합니다.");
        }
        Set<String> seen = new HashSet<>();
        for (Object entry : values) {
            if (!(entry instanceof String code) || !Genre.isValidCode(code) || !seen.add(code)) {
                throw ApiException.validation("유효한 장르 코드를 중복 없이 전달해주세요.");
            }
        }
        return values.stream().map(String.class::cast).toList();
    }
}
