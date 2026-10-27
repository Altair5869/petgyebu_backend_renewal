package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExpensePatchTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-12T09:00:00+09:00");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-13T09:00:00+09:00");

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    @Test
    void omittedFieldsKeepCurrentValuesAndNullMemoClears() {
        Expense current = new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T0, null);

        Expense kept = ExpensePatch.from(json("{}"), TODAY).applyTo(current, T1);

        assertThat(kept).isEqualTo(
                new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T1, null));
        assertThat(ExpensePatch.from(json("{\"memo\":null}"), TODAY).applyTo(current, T1).memo()).isNull();
    }
}
