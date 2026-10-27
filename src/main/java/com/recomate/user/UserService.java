package com.recomate.user;

import com.recomate.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;
    private final IdentityDirectory identities;
    private final SessionDataEraser sessions;
    private final Clock clock;

    public UserService(UserRepository users, IdentityDirectory identities, SessionDataEraser sessions, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.sessions = sessions;
        this.clock = clock;
    }

    public UserRecord profile(String userId) {
        UserRecord user = users.find(userId).orElseGet(() -> {
            // Only initialize once. Cognito/JWT nickname never overwrites an app nickname.
            var seed = identities.profile(userId);
            return users.createIfAbsent(new UserRecord(userId, seed.email(), UserInput.nickname(seed.nickname()),
                    List.of(), UserRecord.Status.ACTIVE, null));
        });
        requireActive(user);
        return user;
    }

    public UserRecord updateNickname(String userId, String nickname) {
        nickname = UserInput.nickname(nickname);
        profile(userId);
        return users.updateNickname(userId, nickname);
    }

    public List<String> genres(String userId) {
        return users.find(userId).map(user -> { requireActive(user); return user.genres(); }).orElse(List.of());
    }

    public List<String> replaceGenres(String userId, List<String> genres) {
        genres = UserInput.genres(genres);
        profile(userId);
        return users.replaceGenres(userId, genres).genres();
    }

    public void delete(String userId) {
        UserRecord state = users.beginDeletion(userId);
        if (state.status() == UserRecord.Status.DELETED) return;
        // Each step is idempotent. Never mark complete before every external deletion succeeds.
        sessions.deleteAll(userId);
        identities.delete(userId);
        // Max Cognito ID-token lifetime is 24h; 48h also covers clock skew. TTL is asynchronous.
        users.completeDeletion(userId, clock.instant().plus(Duration.ofHours(48)).getEpochSecond());
    }

    private static void requireActive(UserRecord user) {
        if (!user.active()) throw ApiException.unauthorized();
    }
}
