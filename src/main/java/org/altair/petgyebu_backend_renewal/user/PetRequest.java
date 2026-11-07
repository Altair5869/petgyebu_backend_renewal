package org.altair.petgyebu_backend_renewal.user;

import jakarta.validation.constraints.NotNull;

// 펫 선택 본문. CAT/DOG 허용은 Service 규칙이 판단한다.
record PetRequest(@NotNull(message = "CAT 또는 DOG만 허용합니다.") String petType) {
}
