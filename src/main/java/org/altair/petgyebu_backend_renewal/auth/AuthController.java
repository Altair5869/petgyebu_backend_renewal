package org.altair.petgyebu_backend_renewal.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // credential: 카카오는 SDK 액세스 토큰, 구글은 ID 토큰.
    record LoginRequest(@NotBlank @Size(max = 4096) String credential) {
    }

    record RefreshTokenRequest(@NotBlank String refreshToken) {
    }

    record TokenResponse(String accessToken, String refreshToken, long accessExpiresInSeconds) {

        static TokenResponse of(AuthService.Tokens tokens) {
            return new TokenResponse(tokens.accessToken(), tokens.refreshToken(),
                    TokenIssuer.ACCESS_TOKEN_TTL.toSeconds());
        }
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login/{provider}")
    TokenResponse login(@PathVariable String provider, @Valid @RequestBody LoginRequest request) {
        Provider parsed = Provider.from(provider)
                .orElseThrow(() -> ApiException.invalidRequest("provider", "kakao 또는 google만 지원합니다."));
        return TokenResponse.of(auth.login(parsed, request.credential()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }
}
