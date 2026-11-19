package com.recomate.content;

import com.recomate.common.ApiException;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContentDetail;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import com.recomate.watched.WatchedRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ContentDetailApiTests {
    private static final String OWNER = "owner-sub";
    private static final String PATH = "/api/contents/movie/603";
    private static final UserRecord ACTIVE =
            new UserRecord(OWNER, "test@example.test", "정우", List.of(), UserRecord.Status.ACTIVE, null);
    @Autowired MockMvc mvc;
    @Autowired ContentDetailService service;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser sessions;
    @MockitoBean WatchedRepository watched;
    @MockitoBean TmdbClient tmdb;

    @BeforeEach
    void defaults() {
        // Title data stays in memory between requests, so every test starts with an empty cache.
        service.clearCache();
        when(users.find(anyString())).thenReturn(Optional.empty());
        when(users.find(OWNER)).thenReturn(Optional.of(ACTIVE));
    }

    private static TmdbContentDetail matrix() {
        return new TmdbContentDetail(603, ContentType.MOVIE, "매트릭스", "The Matrix", "가상 현실", "/p.jpg", null,
                "1999-03-31", 1999, 8.2, 26000, List.of("액션", "SF"), 136, null, null, List.of("Lana Wachowski"),
                List.of(new TmdbContentDetail.Cast("키아누 리브스", "Neo", null)),
                List.of(new TmdbContentDetail.Provider(8, "Netflix", "/n.jpg")), List.of("US"));
    }

    @Test
    void missingTokenIsRejectedWithoutCallingTmdb() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(tmdb, watched);
    }

    @Test
    void detailReturnsScreenFieldsAndWatchedFlag() throws Exception {
        when(tmdb.contentDetail(ContentType.MOVIE, 603)).thenReturn(Optional.of(matrix()));
        when(watched.contains(OWNER, "movie:603")).thenReturn(true);

        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tmdbId").value(603))
                .andExpect(jsonPath("$.mediaType").value("movie"))
                .andExpect(jsonPath("$.title").value("매트릭스"))
                .andExpect(jsonPath("$.originalTitle").value("The Matrix"))
                .andExpect(jsonPath("$.overview").value("가상 현실"))
                .andExpect(jsonPath("$.releaseYear").value(1999))
                .andExpect(jsonPath("$.genres[1]").value("SF"))
                .andExpect(jsonPath("$.runtime").value(136))
                .andExpect(jsonPath("$.creators[0]").value("Lana Wachowski"))
                .andExpect(jsonPath("$.cast[0].name").value("키아누 리브스"))
                .andExpect(jsonPath("$.cast[0].character").value("Neo"))
                .andExpect(jsonPath("$.cast[0].profilePath").doesNotExist())
                .andExpect(jsonPath("$.providers[0].name").value("Netflix"))
                .andExpect(jsonPath("$.providers[0].id").value(8))
                .andExpect(jsonPath("$.countries[0]").value("US"))
                .andExpect(jsonPath("$.watched").value(true))
                // Missing TMDB values are omitted rather than sent as null.
                .andExpect(jsonPath("$.backdropPath").doesNotExist())
                .andExpect(jsonPath("$.seasons").doesNotExist());
    }

    @Test
    void titleDataIsCachedButWatchedIsReadForEveryRequest() throws Exception {
        when(tmdb.contentDetail(ContentType.MOVIE, 603)).thenReturn(Optional.of(matrix()));
        when(watched.contains(OWNER, "movie:603")).thenReturn(false, true);

        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.watched").value(false));
        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.watched").value(true));

        verify(tmdb, times(1)).contentDetail(ContentType.MOVIE, 603);
        verify(watched, times(2)).contains(OWNER, "movie:603");
    }

    @Test
    void movieAndTvWithTheSameIdAreDifferentTitles() throws Exception {
        when(tmdb.contentDetail(ContentType.MOVIE, 603)).thenReturn(Optional.of(matrix()));
        when(tmdb.contentDetail(ContentType.TV, 603)).thenReturn(Optional.empty());

        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isOk());
        mvc.perform(get("/api/contents/tv/603").with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingTitleIsNotFoundAndNotCached() throws Exception {
        when(tmdb.contentDetail(ContentType.MOVIE, 603)).thenReturn(Optional.empty(), Optional.of(matrix()));

        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("콘텐츠를 찾을 수 없습니다."));
        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isOk());
    }

    @Test
    void tmdbFailureIsUpstreamErrorAndRetriedNextTime() throws Exception {
        when(tmdb.contentDetail(ContentType.MOVIE, 603)).thenThrow(ApiException.upstream())
                .thenReturn(Optional.of(matrix()));

        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/contents/person/603", "/api/contents/MOVIE/603", "/api/contents/movie/0",
            "/api/contents/movie/-1", "/api/contents/movie/abc", "/api/contents/movie/1234567890123456789"})
    void invalidTypeOrIdIsRejectedWithoutCallingTmdb(String path) throws Exception {
        mvc.perform(get(path).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(tmdb, watched);
    }

    @Test
    void withdrawnUserIsRejected() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(
                new UserRecord(OWNER, "test@example.test", "정우", List.of(), UserRecord.Status.DELETING, null)));
        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER)))).andExpect(status().isUnauthorized());
        verifyNoInteractions(tmdb, watched);
    }
}
