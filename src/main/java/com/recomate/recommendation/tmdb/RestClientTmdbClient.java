package com.recomate.recommendation.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.recomate.common.ApiException;
import com.recomate.genre.Genre;
import com.recomate.recommendation.ContentType;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestClientTmdbClient implements TmdbClient {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final RestClient http;

    public RestClientTmdbClient(
        @Value("${recomate.tmdb.base-url:https://api.themoviedb.org/3}") String baseUrl,
        @Value("${recomate.tmdb.access-token:}") String accessToken) {
        // Two sequential lookups must fail as 502 before the 28-second Lambda timeout.
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requests.setReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requests)
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
            throw ApiException.upstream();
        }
    }

    @Override
    public List<TmdbContent> discoverPopular(ContentType type, Genre genre, String country, DiscoverSort sort) {
        List<Integer> ids = genre == null ? List.of() : TmdbGenreMapper.idsFor(type, List.of(genre));
        if (genre != null && ids.isEmpty()) return List.of(); // 이 매체에 매핑이 없는 장르 (예: TV의 로맨스·공포)
        try {
            DiscoverResponse body = http.get().uri(uriBuilder -> {
                uriBuilder.path("/discover/{type}")
                        .queryParam("language", "ko-KR")
                        .queryParam("sort_by", sort.sortBy(type))
                        .queryParam("vote_count.gte", sort.minVotes())
                        .queryParam("include_adult", false)
                        .queryParam("page", 1);
                // 최신순은 개봉·방영 전 작품을 제외한다.
                if (sort == DiscoverSort.LATEST) {
                    uriBuilder.queryParam(DiscoverSort.dateField(type) + ".lte", LocalDate.now(KOREA));
                }
                if (!ids.isEmpty()) uriBuilder.queryParam("with_genres", ids.get(0));
                if (country != null) uriBuilder.queryParam("with_origin_country", country);
                return uriBuilder.build(type.value());
            }).retrieve().body(DiscoverResponse.class);
            if (body == null || body.results() == null) return List.of();
            return body.results().stream().map(item -> toContent(type, item)).toList();
        } catch (RestClientException e) {
            throw ApiException.upstream();
        }
    }

    @Override
    public Optional<TmdbContent> details(ContentType type, long id) {
        try {
            Detail body = http.get().uri(u -> u.path("/{type}/{id}").queryParam("language", "ko-KR")
                    .build(type.value(), id)).retrieve().body(Detail.class);
            // Discovery excludes adult titles; keep the same rule for direct lookups.
            if (body == null || body.adult()) return Optional.empty();
            List<Integer> genreIds = body.genres() == null ? List.of() : body.genres().stream().map(GenreRef::id).toList();
            return Optional.of(toContent(type, new Item(body.id(), body.title(), body.name(), body.posterPath(),
                    body.releaseDate(), body.firstAirDate(), body.voteAverage(), body.voteCount(), body.popularity(),
                    genreIds)));
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw ApiException.upstream();
        }
    }

    static final int CAST_LIMIT = 5;
    static final int DIRECTOR_LIMIT = 3;
    static final String PROVIDER_REGION = "KR";

    @Override
    public Optional<TmdbContentDetail> contentDetail(ContentType type, long id) {
        try {
            // One call: credits and watch providers ride along so the 28-second Lambda budget is never at risk.
            FullDetail body = http.get().uri(u -> u.path("/{type}/{id}").queryParam("language", "ko-KR")
                    .queryParam("append_to_response", "{append}")
                    .build(type.value(), id, "credits,watch/providers")).retrieve().body(FullDetail.class);
            if (body == null || body.adult()) return Optional.empty();
            return Optional.of(toDetail(type, body));
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw ApiException.upstream();
        }
    }

    private static TmdbContentDetail toDetail(ContentType type, FullDetail body) {
        boolean movie = type == ContentType.MOVIE;
        String date = blankToNull(movie ? body.releaseDate() : body.firstAirDate());
        Integer year = (date != null && date.length() >= 4) ? Integer.valueOf(date.substring(0, 4)) : null;
        List<String> genres = body.genres() == null ? List.of()
                : body.genres().stream().map(GenreRef::name).filter(name -> blankToNull(name) != null).toList();
        Integer runtime = movie ? positive(body.runtime())
                : body.episodeRunTime() == null ? null
                : body.episodeRunTime().stream().map(RestClientTmdbClient::positive).filter(Objects::nonNull)
                        .findFirst().orElse(null);
        List<String> creators = movie
                ? (body.credits() == null || body.credits().crew() == null ? List.<String>of()
                        : body.credits().crew().stream().filter(c -> "Director".equals(c.job())).map(CrewRef::name)
                                .filter(name -> blankToNull(name) != null).distinct().limit(DIRECTOR_LIMIT).toList())
                : (body.createdBy() == null ? List.<String>of()
                        : body.createdBy().stream().map(PersonRef::name).filter(name -> blankToNull(name) != null)
                                .distinct().limit(DIRECTOR_LIMIT).toList());
        List<TmdbContentDetail.Cast> cast = body.credits() == null || body.credits().cast() == null ? List.of()
                : body.credits().cast().stream().filter(c -> blankToNull(c.name()) != null)
                        .sorted(Comparator.comparingInt(CastRef::order)).limit(CAST_LIMIT)
                        .map(c -> new TmdbContentDetail.Cast(c.name(), blankToNull(c.character()), c.profilePath()))
                        .toList();
        List<TmdbContentDetail.Provider> providers = providersIn(body.watchProviders());
        return new TmdbContentDetail(body.id(), type, movie ? body.title() : body.name(),
                blankToNull(movie ? body.originalTitle() : body.originalName()), blankToNull(body.overview()),
                body.posterPath(), body.backdropPath(), date, year, body.voteAverage(), body.voteCount(), genres,
                runtime, movie ? null : positive(body.numberOfSeasons()), movie ? null : positive(body.numberOfEpisodes()),
                creators, cast, providers, countriesIn(body));
    }

    /**
     * Country codes of origin, the same basis as the popular list's with_origin_country filter.
     * Falls back to production countries when TMDB has no origin country.
     */
    private static List<String> countriesIn(FullDetail body) {
        List<String> origin = countryCodes(body.originCountry() == null ? List.of() : body.originCountry());
        if (!origin.isEmpty()) return origin;
        return countryCodes(body.productionCountries() == null ? List.of()
                : body.productionCountries().stream().map(CountryRef::iso).toList());
    }

    /** Two-letter ISO 3166-1 codes only, upper case, without duplicates. */
    private static List<String> countryCodes(List<String> raw) {
        return raw.stream().filter(Objects::nonNull).map(code -> code.strip().toUpperCase(Locale.ROOT))
                .filter(code -> code.matches("[A-Z]{2}")).distinct().toList();
    }

    /** Subscription (flatrate) services in Korea only; rent and buy offers are left out. */
    private static List<TmdbContentDetail.Provider> providersIn(WatchProviders watch) {
        if (watch == null || watch.results() == null) return List.of();
        RegionProviders region = watch.results().get(PROVIDER_REGION);
        if (region == null || region.flatrate() == null) return List.of();
        return region.flatrate().stream().filter(p -> blankToNull(p.providerName()) != null)
                .sorted(Comparator.comparingInt(ProviderRef::displayPriority))
                .map(p -> new TmdbContentDetail.Provider(p.providerId(), p.providerName(), p.logoPath()))
                .distinct().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static TmdbContent toContent(ContentType type, Item item) {
        boolean movie = type == ContentType.MOVIE;
        String date = movie ? item.releaseDate() : item.firstAirDate();
        Integer year = (date != null && date.length() >= 4) ? Integer.valueOf(date.substring(0, 4)) : null;
        return new TmdbContent(item.id(), type, movie ? item.title() : item.name(), item.posterPath(), year,
                item.voteAverage(), item.voteCount(), item.popularity(),
                TmdbGenreMapper.toInternal(type, item.genreIds() == null ? List.of() : item.genreIds()),
                blankToNull(date));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(long id, String title, String name, boolean adult,
                  @JsonProperty("poster_path") String posterPath,
                  @JsonProperty("release_date") String releaseDate,
                  @JsonProperty("first_air_date") String firstAirDate,
                  @JsonProperty("vote_average") double voteAverage,
                  @JsonProperty("vote_count") int voteCount,
                  double popularity,
                  List<GenreRef> genres) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenreRef(int id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FullDetail(long id, String title, String name, boolean adult,
                      @JsonProperty("original_title") String originalTitle,
                      @JsonProperty("original_name") String originalName,
                      String overview,
                      @JsonProperty("poster_path") String posterPath,
                      @JsonProperty("backdrop_path") String backdropPath,
                      @JsonProperty("release_date") String releaseDate,
                      @JsonProperty("first_air_date") String firstAirDate,
                      @JsonProperty("vote_average") double voteAverage,
                      @JsonProperty("vote_count") int voteCount,
                      List<GenreRef> genres,
                      Integer runtime,
                      @JsonProperty("episode_run_time") List<Integer> episodeRunTime,
                      @JsonProperty("number_of_seasons") Integer numberOfSeasons,
                      @JsonProperty("number_of_episodes") Integer numberOfEpisodes,
                      @JsonProperty("created_by") List<PersonRef> createdBy,
                      Credits credits,
                      @JsonProperty("watch/providers") WatchProviders watchProviders,
                      @JsonProperty("origin_country") List<String> originCountry,
                      @JsonProperty("production_countries") List<CountryRef> productionCountries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CountryRef(@JsonProperty("iso_3166_1") String iso) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Credits(List<CastRef> cast, List<CrewRef> crew) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CastRef(String name, String character, @JsonProperty("profile_path") String profilePath, int order) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CrewRef(String name, String job) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PersonRef(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WatchProviders(Map<String, RegionProviders> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionProviders(List<ProviderRef> flatrate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderRef(@JsonProperty("provider_id") int providerId,
                       @JsonProperty("provider_name") String providerName,
                       @JsonProperty("logo_path") String logoPath,
                       @JsonProperty("display_priority") int displayPriority) {}
}