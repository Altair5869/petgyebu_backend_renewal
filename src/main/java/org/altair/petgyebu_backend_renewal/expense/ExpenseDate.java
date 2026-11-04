package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// YYYY-MM-DD 문자열이고 2000-01-01부터 서울 기준 오늘까지여야 한다. null도 거부한다.
@Documented
@Constraint(validatedBy = ExpenseDateValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface ExpenseDate {

    String message() default "YYYY-MM-DD 형식이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
