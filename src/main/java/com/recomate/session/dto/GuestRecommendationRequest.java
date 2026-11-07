package com.recomate.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Guest TOP 3 without an account. Same participant input as a session; excluded holds the titles the
 * guest marked "이미 봤어요" in this browser, so the server never stores anything for guests.
 */
public record GuestRecommendationRequest(
        @NotNull @Min(2) @Max(6) Integer participantCount,
        @NotNull @Size(min = 2, max = 6) @Valid List<@NotNull ParticipantRequest> participants,
        @Size(max = 20) @Valid List<@NotNull ExclusionRequest> excluded) {

    public CreateSessionRequest session() { return new CreateSessionRequest(participantCount, participants); }

    public List<ExclusionRequest> excludedOrEmpty() { return excluded == null ? List.of() : excluded; }
}
