package com.recomate.user;

import java.util.List;

/** excludeWatched: drop the user's watched titles from new recommendations; on unless the user turns it off. */
public record UserRecord(String userId, String email, String nickname, List<String> genres,
                         Status status, Long expiresAt, boolean excludeWatched) {
    public UserRecord { genres = List.copyOf(genres); }

    public UserRecord(String userId, String email, String nickname, List<String> genres, Status status, Long expiresAt) {
        this(userId, email, nickname, genres, status, expiresAt, true);
    }

    public enum Status { ACTIVE, DELETING, DELETED }
    public boolean active() { return status == Status.ACTIVE; }
}
