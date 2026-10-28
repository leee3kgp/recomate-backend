package com.recomate.genre;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/genres")
public class GenreController {
    private static final GenresResponse CATALOG = new GenresResponse(Genre.all().stream()
            .map(genre -> new GenreItem(genre.name(), genre.displayName())).toList());

    @GetMapping
    GenresResponse genres() { return CATALOG; }

    public record GenreItem(String code, String name) { }
    public record GenresResponse(List<GenreItem> genres) { }
}
