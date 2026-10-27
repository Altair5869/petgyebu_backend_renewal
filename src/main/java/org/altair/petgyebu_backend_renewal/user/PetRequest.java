package org.altair.petgyebu_backend_renewal.user;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import tools.jackson.databind.JsonNode;

// 펫 선택 본문. 문자열 여부만 확인하고 CAT/DOG 허용은 Service 규칙이 판단한다.
record PetRequest(String petType) {

    static PetRequest from(JsonNode body) {
        RequestBodies.requireObject(body);
        JsonNode node = body.get("petType");
        if (node == null || !node.isString()) {
            throw ApiException.invalidRequest("petType", "CAT 또는 DOG만 허용합니다.");
        }
        return new PetRequest(node.stringValue());
    }
}
