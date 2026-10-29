package com.recomate.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    private ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException validation(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }
    public static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "인증이 필요합니다.");
    }
    public static ApiException nicknameRequired() {
        return new ApiException(409, "NICKNAME_REQUIRED", "닉네임을 1~20자로 다시 입력해주세요.");
    }
    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "세션을 찾을 수 없습니다.");
    }
    public static ApiException recommendationExists() {
        return new ApiException(409, "RECOMMENDATION_EXISTS", "이미 추천 결과가 있습니다.");
    }
}
