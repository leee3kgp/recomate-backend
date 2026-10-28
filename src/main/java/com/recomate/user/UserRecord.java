package com.recomate.user;

import java.util.List;

public record UserRecord(String userId, String email, String nickname, List<String> genres,
                         Status status, Long expiresAt) {
    public UserRecord { genres = List.copyOf(genres); }
    public enum Status { ACTIVE, DELETING, DELETED }
    public boolean active() { return status == Status.ACTIVE; }
}
