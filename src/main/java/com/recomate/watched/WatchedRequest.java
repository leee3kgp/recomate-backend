package com.recomate.watched;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record WatchedRequest(
        @NotNull @Positive Long tmdbId,
        @NotNull @Pattern(regexp = "movie|tv") String mediaType) {
}
