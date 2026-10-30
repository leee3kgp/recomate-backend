package com.recomate.recommendation.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestClientTmdbClient implements TmdbClient {
    private final RestClient http;

    public RestClientTmdbClient(RestClient.Builder builder,
            @Value("${recomate.tmdb.base-url:https://api.themoviedb.org/3}") String baseUrl,
            @Value("${recomate.tmdb.access-token:}") String accessToken) {
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).build();
    }

    @Override
    public List<TmdbContent> discover(ContentType type, Collection<Genre> genres) {
        List<Integer> ids = TmdbGenreMapper.idsFor(type, genres);
        if (ids.isEmpty()) return List.of();
        String withGenres = ids.stream().map(String::valueOf).collect(Collectors.joining("|")); // | = OR
        try {
            DiscoverResponse body = http.get()
                    .uri(u -> u.path("/discover/{type}")
                            .queryParam("with_genres", "{genres}")
                            .queryParam("language", "ko-KR")
                            .queryParam("sort_by", "popularity.desc")
                            .queryParam("vote_count.gte", 200)
                            .queryParam("include_adult", false)
                            .queryParam("page", 1)
                            .build(type.value(), withGenres))
                    .retrieve().body(DiscoverResponse.class);
            if (body == null || body.results() == null) return List.of();
            return body.results().stream().map(item -> toContent(type, item)).toList();
        } catch (RestClientException e) {
            throw ApiException.upstream(); // 아래 5번 참고
        }
    }

    private static TmdbContent toContent(ContentType type, Item item) {
        boolean movie = type == ContentType.MOVIE;
        String date = movie ? item.releaseDate() : item.firstAirDate();
        Integer year = (date != null && date.length() >= 4) ? Integer.valueOf(date.substring(0, 4)) : null;
        return new TmdbContent(item.id(), type, movie ? item.title() : item.name(), item.posterPath(), year,
                item.voteAverage(), item.voteCount(), item.popularity(),
                TmdbGenreMapper.toInternal(type, item.genreIds() == null ? List.of() : item.genreIds()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiscoverResponse(List<Item> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(long id, String title, String name,
                @JsonProperty("poster_path") String posterPath,
                @JsonProperty("release_date") String releaseDate,
                @JsonProperty("first_air_date") String firstAirDate,
                @JsonProperty("vote_average") double voteAverage,
                @JsonProperty("vote_count") int voteCount,
                double popularity,
                @JsonProperty("genre_ids") List<Integer> genreIds) {}
}