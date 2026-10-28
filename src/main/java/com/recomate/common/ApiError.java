package com.recomate.common;

public record ApiError(Detail error) {
    public static ApiError of(String code, String message) {
        return new ApiError(new Detail(code, message));
    }
    public record Detail(String code, String message) { }
}
