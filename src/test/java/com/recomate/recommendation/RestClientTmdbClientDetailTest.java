package com.recomate.recommendation;

import com.recomate.common.ApiException;
import com.recomate.recommendation.tmdb.RestClientTmdbClient;
import com.recomate.recommendation.tmdb.TmdbContentDetail;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A local HTTP server stands in for TMDB so the real client, query and JSON mapping are exercised. */
class RestClientTmdbClientDetailTest {
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static HttpServer server;
    private static RestClientTmdbClient client;

    private static final String MOVIE = """
            {"id": 603, "title": "매트릭스", "original_title": "The Matrix", "adult": false,
             "origin_country": ["us", "US", "USA", ""], "production_countries": [{"iso_3166_1": "AU", "name": "Australia"}],
             "overview": "가상 현실을 깨닫는 이야기", "poster_path": "/poster.jpg", "backdrop_path": "/back.jpg",
             "release_date": "1999-03-31", "vote_average": 8.2, "vote_count": 26000, "runtime": 136,
             "genres": [{"id": 28, "name": "액션"}, {"id": 878, "name": "SF"}],
             "credits": {
               "cast": [
                 {"name": "C6", "character": "c6", "order": 5}, {"name": "키아누 리브스", "character": "Neo", "profile_path": "/k.jpg", "order": 0},
                 {"name": "C3", "character": "", "order": 2}, {"name": "C2", "character": "c2", "order": 1},
                 {"name": "C5", "character": "c5", "order": 4}, {"name": "C4", "character": "c4", "order": 3}],
               "crew": [{"name": "Lana Wachowski", "job": "Director"}, {"name": "Lilly Wachowski", "job": "Director"},
                        {"name": "Joel Silver", "job": "Producer"}, {"name": "Lana Wachowski", "job": "Director"}]},
             "watch/providers": {"results": {
               "KR": {"link": "https://example.test", "flatrate": [
                 {"provider_id": 97, "provider_name": "Watcha", "logo_path": "/w.jpg", "display_priority": 2},
                 {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/n.jpg", "display_priority": 1}],
                 "rent": [{"provider_id": 3, "provider_name": "Google Play", "logo_path": "/g.jpg", "display_priority": 0}]},
               "US": {"flatrate": [{"provider_id": 15, "provider_name": "Hulu", "logo_path": "/h.jpg", "display_priority": 0}]}}}}
            """;

    private static final String TV = """
            {"id": 1399, "name": "왕좌의 게임", "original_name": "Game of Thrones", "adult": false, "overview": "",
             "origin_country": [], "production_countries": [{"iso_3166_1": "GB", "name": "United Kingdom"}, {"iso_3166_1": "US", "name": "United States of America"}],
             "first_air_date": "2011-04-17", "vote_average": 8.5, "vote_count": 25000,
             "episode_run_time": [0, 60], "number_of_seasons": 8, "number_of_episodes": 73,
             "created_by": [{"name": "David Benioff"}, {"name": "D. B. Weiss"}],
             "genres": [{"id": 10765, "name": "Sci-Fi & Fantasy"}, {"id": 18, "name": "드라마"}],
             "credits": {"cast": [], "crew": [{"name": "Someone", "job": "Director"}]}}
            """;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            QUERIES.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body = switch (path) {
                case "/movie/603" -> MOVIE;
                case "/tv/1399" -> TV;
                case "/movie/18" -> """
                        {"id": 18, "title": "adult", "adult": true, "vote_average": 5.0, "vote_count": 10,
                         "popularity": 1.0, "genres": []}""";
                case "/movie/500" -> { status = 500; yield "{}"; }
                default -> { status = 404; yield "{\"status_code\": 34}"; }
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new RestClientTmdbClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void reset() { QUERIES.clear(); }

    @Test
    void movieDetailComesFromOneCallWithCreditsAndKoreanSubscriptionProviders() {
        TmdbContentDetail detail = client.contentDetail(ContentType.MOVIE, 603).orElseThrow();

        assertThat(QUERIES).hasSize(1);
        assertThat(QUERIES.get(0)).contains("language=ko-KR").contains("append_to_response=credits,watch/providers");
        assertThat(detail.title()).isEqualTo("매트릭스");
        assertThat(detail.originalTitle()).isEqualTo("The Matrix");
        assertThat(detail.overview()).isEqualTo("가상 현실을 깨닫는 이야기");
        assertThat(detail.backdropPath()).isEqualTo("/back.jpg");
        assertThat(detail.releaseDate()).isEqualTo("1999-03-31");
        assertThat(detail.releaseYear()).isEqualTo(1999);
        assertThat(detail.genres()).containsExactly("액션", "SF");
        assertThat(detail.runtime()).isEqualTo(136);
        assertThat(detail.seasons()).isNull();
        assertThat(detail.episodes()).isNull();
        assertThat(detail.creators()).containsExactly("Lana Wachowski", "Lilly Wachowski");
        // Billing order, at most five, blank character names become null.
        assertThat(detail.cast()).extracting(TmdbContentDetail.Cast::name)
                .containsExactly("키아누 리브스", "C2", "C3", "C4", "C5");
        assertThat(detail.cast().get(0).profilePath()).isEqualTo("/k.jpg");
        assertThat(detail.cast().get(2).character()).isNull();
        // Korea only, subscription only, TMDB display priority order.
        assertThat(detail.providers()).containsExactly(new TmdbContentDetail.Provider(8, "Netflix", "/n.jpg"),
                new TmdbContentDetail.Provider(97, "Watcha", "/w.jpg"));
        // Origin country wins over production countries; codes are upper-cased, deduplicated and two letters only.
        assertThat(detail.countries()).containsExactly("US");
    }

    @Test
    void tvDetailUsesSeriesFieldsAndKeepsMissingValuesEmpty() {
        TmdbContentDetail detail = client.contentDetail(ContentType.TV, 1399).orElseThrow();

        assertThat(detail.type()).isEqualTo(ContentType.TV);
        assertThat(detail.title()).isEqualTo("왕좌의 게임");
        assertThat(detail.originalTitle()).isEqualTo("Game of Thrones");
        assertThat(detail.overview()).isNull();
        assertThat(detail.releaseYear()).isEqualTo(2011);
        assertThat(detail.runtime()).isEqualTo(60);
        assertThat(detail.seasons()).isEqualTo(8);
        assertThat(detail.episodes()).isEqualTo(73);
        assertThat(detail.creators()).containsExactly("David Benioff", "D. B. Weiss");
        assertThat(detail.cast()).isEmpty();
        assertThat(detail.providers()).isEmpty();
        assertThat(detail.posterPath()).isNull();
        // No origin country: production countries are used instead.
        assertThat(detail.countries()).containsExactly("GB", "US");
    }

    @Test
    void missingAndAdultTitlesAreEmptyAndFailuresAreUpstreamErrors() {
        assertThat(client.contentDetail(ContentType.MOVIE, 404)).isEmpty();
        assertThat(client.contentDetail(ContentType.MOVIE, 18)).isEmpty();
        assertThatThrownBy(() -> client.contentDetail(ContentType.MOVIE, 500))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(502));
    }
}
