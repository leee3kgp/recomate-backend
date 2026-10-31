package com.recomate.session.service;

import com.recomate.common.ApiException;
import java.util.UUID;

/** Path identifiers are standard hyphenated UUIDs; uppercase input maps to the stored lowercase key. */
final class SessionIds {
    private SessionIds() { }

    static String parse(String raw) {
        try {
            String sessionId = UUID.fromString(raw).toString();
            if (sessionId.equalsIgnoreCase(raw)) return sessionId;
        } catch (IllegalArgumentException malformed) {
            // Reported below with the same validation error as a non-canonical UUID.
        }
        throw ApiException.validation("sessionId는 UUID 형식이어야 합니다.");
    }
}
