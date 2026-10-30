package com.recomate.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recomate.genre.Genre;
import com.recomate.recommendation.tmdb.TmdbGenreMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TmdbGenreMapperTest {
    @Test
    void romanceAndHorrorHaveNoTvMapping() {
        assertTrue(TmdbGenreMapper.idsFor(ContentType.TV, List.of(Genre.ROMANCE, Genre.HORROR)).isEmpty());
        assertEquals(List.of(27, 10749),
                TmdbGenreMapper.idsFor(ContentType.MOVIE, List.of(Genre.ROMANCE, Genre.HORROR)));
    }

    @Test
    void sciFiAndFantasyShareOneTvId() {
        assertEquals(List.of(10765), TmdbGenreMapper.idsFor(ContentType.TV, List.of(Genre.SF, Genre.FANTASY)));
    }

    @Test
    void tvSciFiFantasyMapsBackToBoth() {
        assertEquals(Set.of(Genre.SF, Genre.FANTASY), TmdbGenreMapper.toInternal(ContentType.TV, List.of(10765)));
    }

    @Test
    void movieIdsMapBackToInternal() {
        assertEquals(Set.of(Genre.ACTION, Genre.DRAMA), TmdbGenreMapper.toInternal(ContentType.MOVIE, List.of(28, 18, 99999)));
    }
}
