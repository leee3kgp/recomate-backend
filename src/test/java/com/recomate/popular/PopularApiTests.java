package com.recomate.popular;

import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
class PopularApiTests {
    private static final String OWNER = "owner-sub";
    @Autowired MockMvc mvc;
    @Autowired PopularService service;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser sessions;
    @MockitoBean TmdbClient tmdb;

    @BeforeEach
    void defaults() {
        // The service keeps results in memory, so every test starts with an empty cache.
        service.clearCache();
        when(users.find(anyString())).thenReturn(Optional.empty());
    }

    private static TmdbContent tmdbContent(long id, ContentType type, String title, double popularity) {
        return new TmdbContent(id, type, title, "/poster.jpg", 2024, 8.0, 1000, popularity, Set.of(Genre.ACTION));
    }

    @Test
    void missingTokenIsRejectedWithoutCallingTmdb() throws Exception {
        mvc.perform(get("/api/popular"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(tmdb);
    }

    @Test
    void withoutFiltersMergesMoviesAndTvByPopularity() throws Exception {
        when(tmdb.discoverPopular(ContentType.MOVIE, null, null))
                .thenReturn(List.of(tmdbContent(1, ContentType.MOVIE, "영화A", 50)));
        when(tmdb.discoverPopular(ContentType.TV, null, null))
                .thenReturn(List.of(tmdbContent(2, ContentType.TV, "드라마B", 90)));
        mvc.perform(get("/api/popular").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").isEmpty())
                .andExpect(jsonPath("$.country").isEmpty())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("드라마B"))
                .andExpect(jsonPath("$.items[0].type").value("tv"))
                .andExpect(jsonPath("$.items[0].posterPath").value("/poster.jpg"))
                .andExpect(jsonPath("$.items[1].title").value("영화A"));
    }

    @Test
    void filtersAreNormalizedAndPassedToTmdb() throws Exception {
        when(tmdb.discoverPopular(any(), eq(Genre.ACTION), eq("KR"))).thenReturn(List.of());
        mvc.perform(get("/api/popular?genre=action&country=kr").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value("ACTION"))
                .andExpect(jsonPath("$.country").value("KR"));
        verify(tmdb).discoverPopular(ContentType.MOVIE, Genre.ACTION, "KR");
        verify(tmdb).discoverPopular(ContentType.TV, Genre.ACTION, "KR");
    }

    @Test
    void genreWithoutTvMappingQueriesMoviesOnly() throws Exception {
        when(tmdb.discoverPopular(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/popular?genre=HORROR").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk());
        verify(tmdb).discoverPopular(ContentType.MOVIE, Genre.HORROR, null);
        verify(tmdb, never()).discoverPopular(eq(ContentType.TV), any(), any());
    }

    @Test
    void blankFiltersAreTreatedAsAbsent() throws Exception {
        when(tmdb.discoverPopular(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/popular").param("genre", "").param("country", " ")
                        .with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").isEmpty());
        verify(tmdb).discoverPopular(ContentType.MOVIE, null, null);
    }

    @Test
    void invalidGenreIsRejectedBeforeTmdb() throws Exception {
        mvc.perform(get("/api/popular?genre=WESTERN").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(tmdb);
    }

    @ParameterizedTest
    @ValueSource(strings = {"KOR", "1", "ZZ"})
    void invalidCountryIsRejectedBeforeTmdb(String country) throws Exception {
        mvc.perform(get("/api/popular?country=" + country).with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(tmdb);
    }

    @Test
    void upstreamFailureReturns502AndIsNotCached() throws Exception {
        when(tmdb.discoverPopular(ContentType.MOVIE, null, null))
                .thenThrow(ApiException.upstream())
                .thenReturn(List.of(tmdbContent(1, ContentType.MOVIE, "영화A", 50)));
        when(tmdb.discoverPopular(ContentType.TV, null, null)).thenReturn(List.of());
        mvc.perform(get("/api/popular").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
        mvc.perform(get("/api/popular").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("영화A"));
    }

    @Test
    void withdrawnUsersAreRejectedEvenWithAnUnexpiredToken() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(
                new UserRecord(OWNER, null, null, List.of(), UserRecord.Status.DELETED, null)));
        mvc.perform(get("/api/popular").with(jwt().jwt(token -> token.subject(OWNER))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(tmdb);
    }
}
