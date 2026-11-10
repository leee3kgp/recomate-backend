package com.recomate.popular;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 장르·국가별 인기 목록. 로그인(JWT)이 필요하며 SecurityConfig의 기본 인증 규칙을 따른다. */
@RestController
@RequestMapping("/api/popular")
public class PopularController {
    private final PopularService service;

    public PopularController(PopularService service) {
        this.service = service;
    }

    @GetMapping
    public PopularResponse popular(@RequestParam(required = false) String genre,
                                   @RequestParam(required = false) String country) {
        return service.popular(genre, country);
    }
}
