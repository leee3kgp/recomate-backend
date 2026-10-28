package com.recomate.user;

import com.recomate.genre.Genre;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser sessions;
    private static final String OWNER = "owner-sub";
    private static final UserRecord USER = new UserRecord(OWNER, "owner@example.test", "정우", List.of(), UserRecord.Status.ACTIVE, null);

    @BeforeEach
    void defaults() {
        when(users.find(anyString())).thenReturn(Optional.empty());
        when(identities.profile(OWNER)).thenReturn(new IdentityDirectory.ProfileSeed(USER.email(), USER.nickname()));
        when(users.createIfAbsent(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void firstProfileUsesVerifiedSubAndCognitoSeed() throws Exception {
        mvc.perform(get("/api/users/me?userId=victim").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(OWNER))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.email").value(USER.email())).andExpect(jsonPath("$.nickname").value("정우"));
        verify(identities).profile(OWNER);
        verify(users, never()).find("victim");
    }

    @Test
    void oldTokenNicknameDoesNotOverwriteProfile() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(USER));
        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER).claim("nickname", "old"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("정우"));
        verifyNoInteractions(identities);
        verify(users, never()).createIfAbsent(any());
    }

    @Test
    void nicknameIsTrimmedAndOnlyAppDatabaseIsUpdated() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(USER));
        when(users.updateNickname(OWNER, "새 이름")).thenReturn(new UserRecord(OWNER, USER.email(), "새 이름", List.of(), UserRecord.Status.ACTIVE, null));
        mvc.perform(patch("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"  새 이름  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새 이름"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        verify(users).updateNickname(OWNER, "새 이름");
        verifyNoInteractions(identities);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "123456789012345678901"})
    void validNicknameChangeCanRepairInvalidSignupNicknameBeforeFirstProfile(String signupNickname) throws Exception {
        when(identities.profile(OWNER)).thenReturn(new IdentityDirectory.ProfileSeed(USER.email(), signupNickname));
        when(users.updateNickname(OWNER, "정우")).thenReturn(USER);
        mvc.perform(patch("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"정우\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("정우"));
        verify(users).createIfAbsent(argThat(user -> "정우".equals(user.nickname())));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "123456789012345678901"})
    void invalidSignupNicknameRequestsReentryWithoutCreatingProfile(String signupNickname) throws Exception {
        when(identities.profile(OWNER)).thenReturn(new IdentityDirectory.ProfileSeed(USER.email(), signupNickname));
        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_REQUIRED"));
        verify(users, never()).createIfAbsent(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "123456789012345678901"})
    void genresCannotCreateProfileWithInvalidSignupNickname(String signupNickname) throws Exception {
        when(identities.profile(OWNER)).thenReturn(new IdentityDirectory.ProfileSeed(USER.email(), signupNickname));
        mvc.perform(put("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genres\":[\"ACTION\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_REQUIRED"));
        verify(users, never()).createIfAbsent(any());
        verify(users, never()).replaceGenres(anyString(), anyList());
    }

    @Test
    void providerOutageRemainsInternalErrorInsteadOfRequestingNickname() throws Exception {
        when(identities.profile(OWNER)).thenThrow(new IllegalStateException("private provider diagnostic"));
        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        verify(users, never()).createIfAbsent(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"nickname\":null}", "{\"nickname\":1}",
            "{\"nickname\":\"\"}", "{\"nickname\":\"   \"}", "{\"nickname\":\"123456789012345678901\"}",
            "{\"nickname\":\"정우\",\"userId\":\"victim\"}", "{\"email\":\"other@example.test\"}",
            "{\"nickname\":\"정우\",\"email\":\"other@example.test\"}"})
    void invalidNicknameRequestsReturnShared400(String body) throws Exception {
        mvc.perform(patch("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(users, never()).updateNickname(anyString(), anyString());
    }

    @Test
    void missingGenresAreEmptyWithoutCreatingProfile() throws Exception {
        mvc.perform(get("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.genres").isEmpty());
        verify(users, never()).createIfAbsent(any());
        verifyNoInteractions(identities);
    }

    @Test
    void genresAreReplacedAndEmptyArrayClearsThem() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(USER));
        when(users.replaceGenres(eq(OWNER), anyList())).thenAnswer(call -> new UserRecord(OWNER, USER.email(), USER.nickname(), call.getArgument(1), UserRecord.Status.ACTIVE, null));
        mvc.perform(put("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genres\":[\"ACTION\",\"SF\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.genres[0]").value("ACTION"))
                .andExpect(jsonPath("$.genres[1]").value("SF"));
        mvc.perform(put("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genres\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.genres").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"genres\":null}", "{\"genres\":\"ACTION\"}", "{\"genres\":[null]}",
            "{\"genres\":[1]}", "{\"genres\":[\"action\"]}", "{\"genres\":[\"UNKNOWN\"]}",
            "{\"genres\":[\"ACTION\",\"ACTION\"]}", "{\"genres\":[],\"userId\":\"victim\"}"})
    void invalidGenreRequestsDoNotWrite(String body) throws Exception {
        mvc.perform(put("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(users, never()).replaceGenres(anyString(), anyList());
    }

    @Test
    void everyCatalogGenreCanBeSavedAsAPreference() throws Exception {
        List<String> codes = Genre.all().stream().map(Enum::name).toList();
        when(users.find(OWNER)).thenReturn(Optional.of(USER));
        when(users.replaceGenres(OWNER, codes)).thenReturn(new UserRecord(
                OWNER, USER.email(), USER.nickname(), codes, UserRecord.Status.ACTIVE, null));
        String body = "{\"genres\":[\"" + String.join("\",\"", codes) + "\"]}";
        mvc.perform(put("/api/users/me/genres").with(jwt().jwt(token -> token.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres", org.hamcrest.Matchers.contains(codes.toArray(String[]::new))));
        verify(users).replaceGenres(OWNER, codes);
    }

    @Test
    void allFiveRoutesRejectMissingAuthentication() throws Exception {
        for (var request : List.of(get("/api/users/me"), patch("/api/users/me"), delete("/api/users/me"),
                get("/api/users/me/genres"), put("/api/users/me/genres"))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(users, identities, sessions);
    }

    @Test
    void deletionReturns204AfterAllStepsSucceed() throws Exception {
        when(users.beginDeletion(OWNER)).thenReturn(new UserRecord(OWNER, null, null, List.of(), UserRecord.Status.DELETING, null));
        mvc.perform(delete("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var order = inOrder(users, sessions, identities);
        order.verify(users).beginDeletion(OWNER);
        order.verify(sessions).deleteAll(OWNER);
        order.verify(identities).delete(OWNER);
        order.verify(users).completeDeletion(eq(OWNER), anyLong());
    }

    @Test
    void deletionStateWriteMustSucceedBeforeAnyExternalDeletion() throws Exception {
        when(users.beginDeletion(OWNER)).thenThrow(new IllegalStateException("private storage diagnostic"));
        mvc.perform(delete("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        verifyNoInteractions(identities, sessions);
        verify(users, never()).completeDeletion(anyString(), anyLong());
    }

    @Test
    void completedWithdrawalCanBeRepeatedWithoutDeletingExternalDataAgain() throws Exception {
        when(users.beginDeletion(OWNER)).thenReturn(new UserRecord(OWNER, null, null, List.of(), UserRecord.Status.DELETED, 123L));
        mvc.perform(delete("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verifyNoInteractions(identities, sessions);
        verify(users, never()).completeDeletion(anyString(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = UserRecord.Status.class, names = {"DELETING", "DELETED"})
    void deletingUsersCannotReadOrWriteUsingUnexpiredJwt(UserRecord.Status state) throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(new UserRecord(OWNER, null, null, List.of(), state, null)));
        for (var request : List.of(get("/api/users/me"), get("/api/users/me/genres"),
                patch("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"x\"}"),
                put("/api/users/me/genres").contentType(MediaType.APPLICATION_JSON).content("{\"genres\":[]}"),
                get("/api/sessions"))) {
            mvc.perform(request.with(jwt().jwt(token -> token.subject(OWNER))))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(identities, sessions);
    }

    @Test
    void storageFailureIsNotDisguisedAsAuthenticationFailure() throws Exception {
        when(users.find(OWNER)).thenThrow(new IllegalStateException("private storage diagnostic"));
        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void failedWithdrawalDoesNotReportSuccessOrMarkComplete() throws Exception {
        when(users.beginDeletion(OWNER)).thenReturn(new UserRecord(OWNER, null, null, List.of(), UserRecord.Status.DELETING, null));
        doThrow(new IllegalStateException("private Cognito diagnostic")).when(identities).delete(OWNER);
        mvc.perform(delete("/api/users/me").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        verify(users, never()).completeDeletion(anyString(), anyLong());
    }
}
