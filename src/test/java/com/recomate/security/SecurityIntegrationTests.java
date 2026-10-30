package com.recomate.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.recomate.user.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real signed JWTs and a local JWK server exercise the production decoder without AWS. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityIntegrationTests.ProtectedEndpoint.class)
class SecurityIntegrationTests {
    private static final String CLIENT = "test-client";
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String ISSUER;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("recomate.auth.issuer", () -> ISSUER);
        registry.add("recomate.auth.client-id", () -> CLIENT);
    }

    @AfterAll
    static void stopJwks() { JWKS.stop(0); }

    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;

    @Test
    void signedIdTokenExposesVerifiedSubject() throws Exception {
        mvc.perform(get("/api/security-test").header("Authorization", "Bearer " + token(builder -> { })))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("owner-sub"));
    }

    @Test
    void signedIdTokenCanReadTheGenreCatalog() throws Exception {
        mvc.perform(get("/api/genres").header("Authorization", "Bearer " + token(builder -> { })))
                .andExpect(status().isOk()).andExpect(jsonPath("$.genres.length()").value(10))
                .andExpect(jsonPath("$.genres[0].code").value("ACTION"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "issuer", "audience", "access-token", "expired", "future", "subject", "no-expiry", "signature"})
    void invalidCredentialsAreRejected(String scenario) throws Exception {
        String bearer = null;
        if (!scenario.equals("missing")) {
            bearer = scenario.equals("malformed") ? "not-a-jwt" : token(builder -> {
                switch (scenario) {
                    case "issuer" -> builder.issuer("https://untrusted.invalid");
                    case "audience" -> builder.audience("other-client");
                    case "access-token" -> builder.claim("token_use", "access");
                    case "expired" -> builder.expirationTime(Date.from(Instant.now().minusSeconds(120)));
                    case "future" -> builder.notBeforeTime(Date.from(Instant.now().plusSeconds(120)));
                    case "subject" -> builder.subject(" ");
                    case "no-expiry" -> builder.expirationTime(null);
                    default -> { }
                }
            }, scenario.equals("signature"));
        }
        for (String path : List.of("/api/security-test", "/api/genres")) {
            var request = get(path);
            if (bearer != null) request.header("Authorization", "Bearer " + bearer);
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void allowedPreflightDoesNotRequireToken() throws Exception {
        mvc.perform(options("/api/users/me").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().exists("Access-Control-Allow-Headers"));
    }

    @Test
    void untrustedOriginDoesNotReceiveCorsPermission() throws Exception {
        mvc.perform(options("/api/users/me").header("Origin", "https://untrusted.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void authenticationFailureIncludesCorsForAllowedBrowser() throws Exception {
        mvc.perform(get("/api/users/me").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void basicAuthenticationIsNotAccepted() throws Exception {
        mvc.perform(get("/api/security-test").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        return token(customize, false);
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customize, boolean wrongKey) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT).subject("owner-sub")
                .claim("token_use", "id").issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        customize.accept(builder);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(KEY.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(wrongKey ? new RSAKeyGenerator(2048).generate() : KEY));
        return jwt.serialize();
    }

    @RestController
    static class ProtectedEndpoint {
        @GetMapping("/api/security-test")
        Map<String, String> currentSubject(@AuthenticationPrincipal Jwt jwt) {
            return Map.of("sub", jwt.getSubject());
        }
    }
}
