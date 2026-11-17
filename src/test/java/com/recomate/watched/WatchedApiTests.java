package com.recomate.watched;

import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.recommendation.tmdb.TmdbContent;
import com.recomate.user.IdentityDirectory;
import com.recomate.user.SessionDataEraser;
import com.recomate.user.UserRecord;
import com.recomate.user.UserRepository;
import com.recomate.watched.WatchedRepository.Cursor;
import com.recomate.watched.WatchedRepository.Page;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WatchedApiTests {
    private static final String OWNER = "owner-sub", OTHER = "other-sub";
    private static final String PATH = "/api/users/me/watched";
    private static final UserRecord ACTIVE = new UserRecord(OWNER, "test@example.test", "정우", List.of(), UserRecord.Status.ACTIVE, null);
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean IdentityDirectory identities;
    @MockitoBean SessionDataEraser sessions;
    @MockitoBean WatchedRepository watched;
    @MockitoBean TmdbClient tmdb;

    @BeforeEach
    void defaults() {
        when(users.find(anyString())).thenReturn(Optional.empty());
        when(users.find(OWNER)).thenReturn(Optional.of(ACTIVE));
    }

    private static WatchedItem item(long id) {
        return new WatchedItem(id, "movie", "title" + id, "/p.jpg", 2024, "2026-11-16T00:00:00.000Z");
    }

    @Test
    void everyRouteRequiresAuthentication() throws Exception {
        for (var request : List.of(get(PATH), post(PATH), delete(PATH + "/movie/1"))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(watched, tmdb);
    }

    @Test
    void listReturnsNewestFirstPageAndAnOwnerBoundToken() throws Exception {
        var cursor = new Cursor("movie:1", "2026-11-16T00:00:00.000Z");
        when(watched.list(OWNER, 1, null)).thenReturn(new Page(List.of(item(1)), cursor));
        String token = com.jayway.jsonpath.JsonPath.read(mvc.perform(get(PATH).param("limit", "1")
                        .with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].tmdbId").value(1)).andExpect(jsonPath("$.items[0].title").value("title1"))
                .andReturn().getResponse().getContentAsString(), "$.nextToken");

        when(watched.list(OWNER, 20, cursor)).thenReturn(new Page(List.of(), null));
        mvc.perform(get(PATH).param("nextToken", token).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextToken").doesNotExist());
        // Another user's token is rejected rather than read under the caller's id.
        mvc.perform(get(PATH).param("nextToken", token).with(jwt().jwt(t -> t.subject(OTHER))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listRejectsBadParameters() throws Exception {
        for (String query : List.of("limit=0", "limit=101", "limit=abc", "nextToken=***", "sort=new", "limit=1&limit=2")) {
            mvc.perform(get(PATH + "?" + query).with(jwt().jwt(t -> t.subject(OWNER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(watched);
    }

    @Test
    void addStoresTmdbDataRatherThanClientData() throws Exception {
        when(tmdb.details(ContentType.TV, 1399L)).thenReturn(Optional.of(
                new TmdbContent(1399L, ContentType.TV, "왕좌의 게임", "/got.jpg", 2011, 8.4, 20000, 90, Set.of(Genre.DRAMA))));
        mvc.perform(post(PATH).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json")
                        .content("{\"tmdbId\":1399,\"mediaType\":\"tv\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("왕좌의 게임"))
                .andExpect(jsonPath("$.mediaType").value("tv")).andExpect(jsonPath("$.releaseYear").value(2011))
                .andExpect(jsonPath("$.watchedAt").isString())
                .andExpect(jsonPath("$.contentKey").doesNotExist());
        var saved = ArgumentCaptor.forClass(WatchedItem.class);
        verify(watched).save(eq(OWNER), saved.capture());
        assertThat(saved.getValue().contentKey()).isEqualTo("tv:1399");
        assertThat(saved.getValue().posterPath()).isEqualTo("/got.jpg");
    }

    @Test
    void unknownTitleIs404AndNothingIsStored() throws Exception {
        when(tmdb.details(ContentType.MOVIE, 9L)).thenReturn(Optional.empty());
        mvc.perform(post(PATH).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json")
                        .content("{\"tmdbId\":9,\"mediaType\":\"movie\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.message").value("콘텐츠를 찾을 수 없습니다."));
        verify(watched, never()).save(any(), any());
    }

    @Test
    void tmdbOutageIs502AndNothingIsStored() throws Exception {
        when(tmdb.details(any(), anyLong())).thenThrow(ApiException.upstream());
        mvc.perform(post(PATH).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json")
                        .content("{\"tmdbId\":9,\"mediaType\":\"movie\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
        verify(watched, never()).save(any(), any());
    }

    @Test
    void malformedAddBodiesAreRejectedBeforeTmdb() throws Exception {
        for (String body : List.of("", "{}", "{\"tmdbId\":1}", "{\"tmdbId\":0,\"mediaType\":\"movie\"}",
                "{\"tmdbId\":1,\"mediaType\":\"MOVIE\"}", "{\"tmdbId\":\"abc\",\"mediaType\":\"tv\"}")) {
            mvc.perform(post(PATH).with(jwt().jwt(t -> t.subject(OWNER))).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(tmdb, watched);
    }

    @Test
    void deleteIsIdempotentAndValidatesThePath() throws Exception {
        mvc.perform(delete(PATH + "/tv/1399").with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(watched).delete(OWNER, "tv:1399");
        for (String path : List.of("/MOVIE/1", "/movie/0", "/movie/abc", "/book/1", "/movie/12345678901234567890")) {
            mvc.perform(delete(PATH + path).with(jwt().jwt(t -> t.subject(OWNER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoMoreInteractions(watched);
    }

    @Test
    void withdrawnUsersCannotUseWatchedRoutes() throws Exception {
        when(users.find(OWNER)).thenReturn(Optional.of(
                new UserRecord(OWNER, null, null, List.of(), UserRecord.Status.DELETING, null)));
        mvc.perform(get(PATH).with(jwt().jwt(t -> t.subject(OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(watched);
    }
}
