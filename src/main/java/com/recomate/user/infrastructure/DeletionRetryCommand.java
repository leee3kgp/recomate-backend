package com.recomate.user.infrastructure;

import com.recomate.user.UserRepository;
import com.recomate.user.UserService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Privileged recovery after a caller's token expires; does not expose a public admin API. */
@Component
@ConditionalOnProperty(name = "recomate.deletion.resume-user-id")
public class DeletionRetryCommand implements ApplicationRunner {
    private final UserService users;
    private final UserRepository repository;
    private final String userId;
    private final ConfigurableApplicationContext context;
    public DeletionRetryCommand(UserService users, UserRepository repository,
            @Value("${recomate.deletion.resume-user-id}") String userId, ConfigurableApplicationContext context) {
        this.users = users;
        this.repository = repository;
        this.userId = userId;
        this.context = context;
    }
    @Override
    public void run(ApplicationArguments arguments) {
        if (!UUID.fromString(userId).toString().equals(userId)) throw new IllegalArgumentException("Expected Cognito sub UUID");
        // Only resume a persisted deletion. A mistyped UUID must never initiate a new withdrawal.
        var record = repository.find(userId).orElseThrow(() -> new IllegalStateException("No deletion to resume"));
        if (record.active()) throw new IllegalStateException("User has not requested deletion");
        try { users.delete(userId); }
        finally { context.close(); }
    }
}
