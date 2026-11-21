package com.recomate.recommendation;

import com.recomate.genre.Genre;
import com.recomate.recommendation.tmdb.DiscoverSort;
import com.recomate.recommendation.tmdb.RestClientTmdbClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 로컬 HTTP 서버를 TMDB 대신 띄워 인기 목록 정렬별 실제 쿼리와 날짜 매핑을 확인한다. */
class RestClientTmdbClientPopularTest {
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static HttpServer server;
    private static RestClientTmdbClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            REQUESTS.add(exchange.getRequestURI().getPath() + "?"
                    + URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"results": [
                      {"id": 1, "title": "영화", "name": "드라마", "release_date": "2026-11-01",
                       "first_air_date": "", "vote_average": 7.5, "vote_count": 300, "popularity": 10.0,
                       "genre_ids": [28]}]}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new RestClientTmdbClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void clear() {
        REQUESTS.clear();
    }

    @Test
    void popularKeepsExistingQuery() {
        client.discoverPopular(ContentType.MOVIE, Genre.ACTION, "KR", DiscoverSort.POPULAR);
        assertThat(REQUESTS.get(0)).startsWith("/discover/movie?")
                .contains("sort_by=popularity.desc", "vote_count.gte=200", "with_genres=28", "with_origin_country=KR")
                .doesNotContain(".lte=");
    }

    @Test
    void ratingSortsByVoteAverageWithVoteFloor() {
        client.discoverPopular(ContentType.TV, null, null, DiscoverSort.RATING);
        assertThat(REQUESTS.get(0)).startsWith("/discover/tv?")
                .contains("sort_by=vote_average.desc", "vote_count.gte=200");
    }

    @Test
    void latestUsesTypeSpecificDateFieldAndExcludesUnreleased() {
        client.discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.LATEST);
        client.discoverPopular(ContentType.TV, null, null, DiscoverSort.LATEST);
        assertThat(REQUESTS.get(0)).contains("sort_by=primary_release_date.desc", "primary_release_date.lte=",
                "vote_count.gte=20");
        assertThat(REQUESTS.get(1)).contains("sort_by=first_air_date.desc", "first_air_date.lte=");
    }

    @Test
    void releaseDateIsMappedAndBlankBecomesNull() {
        var movie = client.discoverPopular(ContentType.MOVIE, null, null, DiscoverSort.POPULAR);
        var tv = client.discoverPopular(ContentType.TV, null, null, DiscoverSort.POPULAR);
        assertThat(movie.get(0).releaseDate()).isEqualTo("2026-11-01");
        assertThat(tv.get(0).releaseDate()).isNull();
    }
}
