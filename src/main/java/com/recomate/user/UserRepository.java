package com.recomate.user;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<UserRecord> find(String userId);
    UserRecord createIfAbsent(UserRecord user);
    UserRecord updateNickname(String userId, String nickname);
    UserRecord replaceGenres(String userId, List<String> genres);
    UserRecord beginDeletion(String userId);
    void completeDeletion(String userId, long expiresAt);
}
