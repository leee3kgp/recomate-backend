package com.recomate.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSessionRequest(
        @NotNull @Min(2) @Max(6) Integer participantCount,
        @NotNull @Size(min = 2, max = 6) @Valid List<@NotNull ParticipantRequest> participants) {
}
