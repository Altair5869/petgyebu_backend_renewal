package org.altair.petgyebu_backend_renewal.expense;

import org.altair.petgyebu_backend_renewal.common.ApiException;

public enum Category {
    FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER;

    // 대소문자를 구분해 이름과 정확히 일치해야 한다. "유효한 카테고리 이름"의 단일 기준이다.
    static boolean isValidName(String text) {
        for (Category category : values()) {
            if (category.name().equals(text)) {
                return true;
            }
        }
        return false;
    }

    // 이름이 맞지 않으면 400 INVALID_REQUEST(category)다.
    public static Category parse(String text) {
        if (!isValidName(text)) {
            throw ApiException.invalidRequest("category", "허용되지 않는 카테고리입니다.");
        }
        return valueOf(text);
    }
}
