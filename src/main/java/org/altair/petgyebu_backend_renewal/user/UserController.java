package org.altair.petgyebu_backend_renewal.user;

import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    record MeResponse(long id, String provider, String petType) {

        static MeResponse of(User user) {
            return new MeResponse(user.id(), user.provider(), user.petType());
        }
    }

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal AuthUser authUser) {
        return MeResponse.of(userService.me(authUser.userId()));
    }

    @PutMapping("/pet")
    MeResponse selectPet(@AuthenticationPrincipal AuthUser authUser, @RequestBody JsonNode body) {
        return MeResponse.of(userService.selectPet(authUser.userId(), body));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@AuthenticationPrincipal AuthUser authUser) {
        userService.withdraw(authUser.userId());
    }
}
