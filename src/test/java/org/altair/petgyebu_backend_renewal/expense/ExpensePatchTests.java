package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;

class ExpensePatchTests {

    private static ExpensePatch memoOnly(JsonNullable<String> memo) {
        return new ExpensePatch(JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), memo);
    }

    @Test
    void omittedFieldsHaveNoNewValue() {
        ExpensePatch empty = memoOnly(JsonNullable.undefined());

        assertThat(empty.newDate()).isNull();
        assertThat(empty.newCategory()).isNull();
        assertThat(empty.newMemo()).isNull();
        assertThat(empty.memo().isPresent()).isFalse();
    }

    @Test
    void nullOrBlankMemoMeansClearAndTextIsKept() {
        assertThat(memoOnly(JsonNullable.of(null)).memo().isPresent()).isTrue();
        assertThat(memoOnly(JsonNullable.of(null)).newMemo()).isNull();
        assertThat(memoOnly(JsonNullable.of("  ")).newMemo()).isNull();
        assertThat(memoOnly(JsonNullable.of("새 메모")).newMemo()).isEqualTo("새 메모");
    }

    @Test
    void presentFieldsAreConvertedForTheUpdate() {
        ExpensePatch patch = new ExpensePatch(JsonNullable.of(500L), JsonNullable.of("2026-09-30"),
                JsonNullable.of("FOOD"), JsonNullable.undefined());

        assertThat(patch.newDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(patch.newCategory()).isEqualTo("FOOD");
    }
}
