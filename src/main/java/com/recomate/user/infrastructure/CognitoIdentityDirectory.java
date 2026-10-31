package com.recomate.user.infrastructure;

import com.recomate.common.ApiException;
import com.recomate.user.IdentityDirectory;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

@Component
public class CognitoIdentityDirectory implements IdentityDirectory {
    private final CognitoIdentityProviderClient client;
    private final String poolId;

    public CognitoIdentityDirectory(CognitoIdentityProviderClient client,
                                    @Value("${recomate.auth.user-pool-id}") String poolId) {
        this.client = client;
        this.poolId = poolId;
    }

    @Override
    public ProfileSeed profile(String userId) {
        try {
            var response = client.adminGetUser(AdminGetUserRequest.builder().userPoolId(poolId).username(userId).build());
            Map<String, String> attributes = response.userAttributes().stream().filter(item -> item.value() != null)
                    .collect(Collectors.toMap(AttributeType::name, AttributeType::value));
            if (!userId.equals(attributes.get("sub")) || !Boolean.TRUE.equals(response.enabled())) {
                throw ApiException.unauthorized();
            }
            if (attributes.get("email") == null) throw new IllegalStateException("Missing signup email");
            return new ProfileSeed(attributes.get("email"), attributes.get("nickname"));
        } catch (UserNotFoundException missingUser) {
            throw ApiException.unauthorized();
        }
    }

    @Override
    public void delete(String userId) {
        try {
            client.adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId(poolId).username(userId).build());
        } catch (UserNotFoundException alreadyDeleted) {
            // Retrying after Cognito succeeded but the DynamoDB completion write failed is safe.
        }
    }
}
