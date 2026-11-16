package com.recomate.session.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ParticipantRequest(
        @NotNull Integer index,
        String name,
        @NotEmpty List<String> genres) {
}
