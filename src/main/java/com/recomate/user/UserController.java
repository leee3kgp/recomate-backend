package com.recomate.user;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @GetMapping
    ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return ProfileResponse.from(users.profile(jwt.getSubject()));
    }

    @PatchMapping
    ProfileResponse update(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        String nickname = UserInput.nickname(UserInput.onlyField(body, "nickname"));
        return ProfileResponse.from(users.updateNickname(jwt.getSubject(), nickname));
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        users.delete(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/genres")
    GenresResponse genres(@AuthenticationPrincipal Jwt jwt) {
        return new GenresResponse(users.genres(jwt.getSubject()));
    }

    @PutMapping("/genres")
    GenresResponse replaceGenres(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        List<String> genres = UserInput.genres(UserInput.onlyField(body, "genres"));
        return new GenresResponse(users.replaceGenres(jwt.getSubject(), genres));
    }

    public record ProfileResponse(String userId, String email, String nickname) {
        static ProfileResponse from(UserRecord user) {
            return new ProfileResponse(user.userId(), user.email(), user.nickname());
        }
    }
    public record GenresResponse(List<String> genres) { }
}
