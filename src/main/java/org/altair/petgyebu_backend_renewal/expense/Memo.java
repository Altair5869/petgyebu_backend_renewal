package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// null·공백만은 통과(저장 시 null). NUL 문자와 100 코드 포인트 초과를 거부한다.
@Documented
@Constraint(validatedBy = MemoValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface Memo {

    String message() default "허용되지 않는 문자가 있습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
