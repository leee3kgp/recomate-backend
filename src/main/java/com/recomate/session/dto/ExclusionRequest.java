package com.recomate.session.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** "이미 봤어요" target: one title from the session's current recommendations. */
public record ExclusionRequest(
        @NotNull @Positive Long tmdbId,
        @NotNull @Pattern(regexp = "movie|tv") String mediaType) {
}
