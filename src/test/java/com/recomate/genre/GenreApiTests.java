package com.recomate.genre;

import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GenreApiTests {
    private static final String OWNER = "owner-sub";
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser sessions;

    @BeforeEach
    void defaults() { when(users.find(anyString())).thenReturn(Optional.empty()); }

    @Test
    void catalogReturnsTheAgreedTenCodesAndNamesInDisplayOrderWithoutCreatingAProfile() throws Exception {
        mvc.perform(get("/api/genres").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("""
                        {"genres":[
                          {"code":"ACTION","name":"액션"},
                          {"code":"COMEDY","name":"코미디"},
                          {"code":"ROMANCE","name":"로맨스"},
                          {"code":"DRAMA","name":"드라마"},
                          {"code":"SF","name":"SF"},
                          {"code":"FANTASY","name":"판타지"},
                          {"code":"HORROR","name":"공포"},
                          {"code":"CRIME","name":"범죄"},
                          {"code":"MYSTERY","name":"미스터리"},
                          {"code":"ANIMATION","name":"애니메이션"}
                        ]}
                        """, JsonCompareMode.STRICT));
        verify(users).find(OWNER);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(identities, sessions);
    }

    @Test
    void activeUsersReceiveTheSameCatalogRegardlessOfSavedPreferences() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, "test@example.test", "정우",
                List.of("HORROR"), UserRecord.Status.ACTIVE, null)));
        mvc.perform(get("/api/genres").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.genres.length()").value(10))
                .andExpect(jsonPath("$.genres[0].code").value("ACTION"));
        verify(users).find(OWNER);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(identities, sessions);
    }

    @Test
    void missingAuthenticationCannotReadTheCatalog() throws Exception {
        mvc.perform(get("/api/genres").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(users, identities, sessions);
    }

    @ParameterizedTest
    @EnumSource(value = UserRecord.Status.class, names = {"DELETING", "DELETED"})
    void withdrawnUsersCannotReadTheCatalogWithAnUnexpiredToken(UserRecord.Status state) throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, null, null, List.of(), state, null)));
        mvc.perform(get("/api/genres").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(identities, sessions);
    }

    @Test
    void failedUserAccessCheckReturnsTheSharedServerError() throws Exception {
        when(users.find(OWNER)).thenThrow(new IllegalStateException("private storage diagnostic"));
        mvc.perform(get("/api/genres").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        verifyNoInteractions(identities, sessions);
    }

    @Test
    void browserPreflightAllowsCatalogGetWithoutAuthenticationOrStorageAccess() throws Exception {
        mvc.perform(options("/api/genres").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")));
        verifyNoInteractions(users, identities, sessions);
    }
}
