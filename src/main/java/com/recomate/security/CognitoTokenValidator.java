package com.recomate.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Additional Cognito ID-token constraints beyond signature, issuer and timestamps. */
public final class CognitoTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Invalid Cognito ID token", null);
    private final String clientId;

    public CognitoTokenValidator(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object audience = jwt.getClaims().get("aud");
        boolean intendedClient = clientId.equals(audience)
                || audience instanceof List<?> values && values.contains(clientId);
        Object subject = jwt.getClaims().get("sub");
        if (!intendedClient || !"id".equals(jwt.getClaims().get("token_use"))
                || !(subject instanceof String sub) || sub.isBlank() || jwt.getExpiresAt() == null) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
