package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// null은 통과(기본값 OTHER)하고, 그 외에는 Category 이름과 정확히 일치해야 한다.
@Documented
@Constraint(validatedBy = CategoryNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface CategoryName {

    String message() default "허용되지 않는 카테고리입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
