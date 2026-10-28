package org.altair.petgyebu_backend_renewal.expense;

import org.altair.petgyebu_backend_renewal.common.ApiException;

public enum Category {
    FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER;

    // 대소문자를 구분해 이름과 정확히 일치해야 한다. 아니면 400 INVALID_REQUEST(category)다.
    public static Category parse(String text) {
        for (Category category : values()) {
            if (category.name().equals(text)) {
                return category;
            }
        }
        throw ApiException.invalidRequest("category", "허용되지 않는 카테고리입니다.");
    }
}
