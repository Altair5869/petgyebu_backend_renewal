package org.altair.petgyebu_backend_renewal.auth;

import java.util.Arrays;
import java.util.Optional;

public enum Provider {
    KAKAO, GOOGLE;

    public static Optional<Provider> from(String value) {
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(value)).findFirst();
    }
}
