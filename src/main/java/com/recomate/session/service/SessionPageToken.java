package com.recomate.session.service;

import com.recomate.common.ApiException;
import com.recomate.session.repository.SessionRepository.Cursor;
import java.io.*;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque navigation data, never authorization. Queries always use the verified JWT subject. */
final class SessionPageToken {
    private SessionPageToken() { }

    static String encode(String userId, Cursor cursor) {
        if (cursor == null) return null;
        try {
            var buffer = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(buffer)) {
                output.writeByte(1);
                output.writeUTF(userId);
                output.writeUTF(cursor.sessionId());
                output.writeUTF(cursor.createdAt());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException unexpected) {
            throw new IllegalStateException("Cannot encode session page", unexpected);
        }
    }

    static Cursor decode(String userId, String token) {
        if (token == null) return null;
        try {
            if (token.isEmpty() || token.length() > 2048 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw new IllegalArgumentException();
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readUnsignedByte() != 1 || !userId.equals(input.readUTF())) throw new IllegalArgumentException();
                String sessionId = input.readUTF(), createdAt = input.readUTF();
                if (!UUID.fromString(sessionId).toString().equals(sessionId) || createdAt.length() > 64
                        || !createdAt.endsWith("Z") || input.available() != 0) throw new IllegalArgumentException();
                Instant.parse(createdAt);
                return new Cursor(sessionId, createdAt);
            }
        } catch (IOException | RuntimeException malformed) {
            throw ApiException.validation("nextToken이 올바르지 않습니다.");
        }
    }
}
