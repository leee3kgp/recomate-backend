package com.recomate.session.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ParticipantRequest(
        @NotNull Integer index,
        @NotEmpty List<String> genres) {
}
