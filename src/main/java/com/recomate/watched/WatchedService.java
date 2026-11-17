package com.recomate.watched;

import com.recomate.common.ApiException;
import com.recomate.recommendation.ContentType;
import com.recomate.recommendation.tmdb.TmdbClient;
import com.recomate.user.UserService;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class WatchedService {
    private final WatchedRepository watched;
    private final UserService users;
    private final TmdbClient tmdb;
    private final Clock clock;

    public WatchedService(WatchedRepository watched, UserService users, TmdbClient tmdb, Clock clock) {
        this.watched = watched;
        this.users = users;
        this.tmdb = tmdb;
        this.clock = clock;
    }

    public WatchedListResponse list(String userId, MultiValueMap<String, String> query) {
        if (query.keySet().stream().anyMatch(key -> !key.equals("limit") && !key.equals("nextToken"))
                || query.values().stream().anyMatch(values -> values.size() != 1)) {
            throw ApiException.validation("조회 파라미터를 확인해주세요.");
        }
        String rawLimit = query.getFirst("limit");
        int limit = 20;
        if (rawLimit != null) {
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw ApiException.validation("limit은 1~100의 정수여야 합니다.");
            limit = Integer.parseInt(rawLimit);
            if (limit > 100) throw ApiException.validation("limit은 1~100의 정수여야 합니다.");
        }
        var page = watched.list(userId, limit, WatchedPageToken.decode(userId, query.getFirst("nextToken")));
        return new WatchedListResponse(page.items(), WatchedPageToken.encode(userId, page.next()));
    }

    /** Title data comes from TMDB, never from the client. */
    public WatchedItem add(String userId, WatchedRequest request) {
        users.profile(userId); // creates the profile row the ACTIVE check in save() relies on
        var type = "movie".equals(request.mediaType()) ? ContentType.MOVIE : ContentType.TV;
        var content = tmdb.details(type, request.tmdbId()).orElseThrow(ApiException::contentNotFound);
        var item = new WatchedItem(content.id(), type.value(), content.title(), content.posterPath(),
                content.releaseYear(), WatchedItem.stamp(clock.instant()));
        watched.save(userId, item);
        return item;
    }

    public void remove(String userId, String mediaType, String rawId) {
        if (!mediaType.matches("movie|tv") || !rawId.matches("[1-9][0-9]{0,17}")) {
            throw ApiException.validation("콘텐츠 유형과 ID를 확인해주세요.");
        }
        watched.delete(userId, mediaType + ":" + rawId);
    }

    public record WatchedListResponse(java.util.List<WatchedItem> items, String nextToken) { }
}
