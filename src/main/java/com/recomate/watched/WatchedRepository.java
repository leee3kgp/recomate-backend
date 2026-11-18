package com.recomate.watched;

import java.util.List;
import java.util.Set;

/** Per-user watched titles, keyed by contentKey ("mediaType:tmdbId"). */
public interface WatchedRepository {
    /** Upserts the title only while the user is ACTIVE; a repeated save moves it to the top. */
    void save(String userId, WatchedItem item);

    /** Newest first. */
    Page list(String userId, int limit, Cursor cursor);

    /** Idempotent: deleting a missing title succeeds. */
    void delete(String userId, String contentKey);

    /** Whether one title is saved, for the content detail screen. */
    boolean contains(String userId, String contentKey);

    /** All contentKeys, for excluding watched titles from new recommendations. */
    Set<String> keys(String userId);

    /** Withdrawal. Runs after DELETING is stored, so gated saves cannot add titles behind it. */
    void deleteAll(String userId);

    record Cursor(String contentKey, String watchedAt) { }
    record Page(List<WatchedItem> items, Cursor next) {
        public Page { items = List.copyOf(items); }
    }
}
