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
}
