package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class MemoValidator implements ConstraintValidator<Memo, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        // PostgreSQL TEXT는 NUL 문자를 저장할 수 없다.
        if (value.indexOf('\0') >= 0) {
            return false;
        }
        if (value.codePointCount(0, value.length()) > ExpenseFields.MEMO_MAX_CODE_POINTS) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("최대 " + ExpenseFields.MEMO_MAX_CODE_POINTS + "자입니다.")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
