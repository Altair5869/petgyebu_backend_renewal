package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;

class ExpensePatchTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-12T09:00:00+09:00");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-13T09:00:00+09:00");

    private static final Expense CURRENT =
            new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T0, null);

    private static ExpensePatch memoOnly(JsonNullable<String> memo) {
        return new ExpensePatch(JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), memo);
    }

    @Test
    void omittedFieldsKeepCurrentValuesAndNullMemoClears() {
        ExpensePatch empty = memoOnly(JsonNullable.undefined());

        assertThat(empty.applyTo(CURRENT, T1)).isEqualTo(
                new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T1, null));
        assertThat(memoOnly(JsonNullable.of(null)).applyTo(CURRENT, T1).memo()).isNull();
        assertThat(memoOnly(JsonNullable.of("  ")).applyTo(CURRENT, T1).memo()).isNull();
        assertThat(memoOnly(JsonNullable.of("새 메모")).applyTo(CURRENT, T1).memo()).isEqualTo("새 메모");
    }

    @Test
    void presentFieldsReplaceCurrentValues() {
        ExpensePatch patch = new ExpensePatch(JsonNullable.of(500L), JsonNullable.of("2026-09-30"),
                JsonNullable.of("FOOD"), JsonNullable.undefined());

        assertThat(patch.applyTo(CURRENT, T1)).isEqualTo(
                new Expense(1L, 7L, 500, LocalDate.of(2026, 9, 30), Category.FOOD, "병원", T0, T1, null));
    }
}
