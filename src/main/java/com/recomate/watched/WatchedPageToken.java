package com.recomate.watched;

import com.recomate.common.ApiException;
import com.recomate.watched.WatchedRepository.Cursor;
import java.io.*;
import java.time.Instant;
import java.util.Base64;

/** Opaque navigation data bound to the owner, never authorization. Same format rules as the session list token. */
final class WatchedPageToken {
    private WatchedPageToken() { }

    static String encode(String userId, Cursor cursor) {
        if (cursor == null) return null;
        try {
            var buffer = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(buffer)) {
                output.writeByte(1);
                output.writeUTF(userId);
                output.writeUTF(cursor.contentKey());
                output.writeUTF(cursor.watchedAt());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException unexpected) {
            throw new IllegalStateException("Cannot encode watched page", unexpected);
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
                String contentKey = input.readUTF(), watchedAt = input.readUTF();
                if (!contentKey.matches("(movie|tv):[1-9][0-9]{0,17}") || watchedAt.length() > 64
                        || !watchedAt.endsWith("Z") || input.available() != 0) throw new IllegalArgumentException();
                Instant.parse(watchedAt);
                return new Cursor(contentKey, watchedAt);
            }
        } catch (IOException | RuntimeException malformed) {
            throw ApiException.validation("nextToken이 올바르지 않습니다.");
        }
    }
}
