package com.recomate.watched;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** One title the user has watched. watchedAt is an ISO-8601 UTC instant set by the server. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WatchedItem(long tmdbId, String mediaType, String title, String posterPath, Integer releaseYear,
                          String watchedAt) {
    // Fixed millisecond width: watchedAt is an index sort key compared as a string.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static String stamp(Instant at) { return STAMP.format(at); }

    public String contentKey() { return mediaType + ":" + tmdbId; }
}
