package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

// 서울 기준 오늘은 주입된 Clock으로 구한다.
class ExpenseDateValidator implements ConstraintValidator<ExpenseDate, String> {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Clock clock;

    ExpenseDateValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            return false;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return false;
        }
        if (date.isBefore(ExpenseFields.MIN_DATE) || date.isAfter(LocalDate.ofInstant(clock.instant(), SEOUL))) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("2000-01-01부터 오늘까지만 허용합니다.").addConstraintViolation();
            return false;
        }
        return true;
    }
}
