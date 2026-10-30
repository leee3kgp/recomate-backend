package com.recomate.recommendation;

public enum ContentType {
    MOVIE("movie"), TV("tv");

    private final String value;
    ContentType(String value) { this.value = value; }
    public String value() { return value; }
}