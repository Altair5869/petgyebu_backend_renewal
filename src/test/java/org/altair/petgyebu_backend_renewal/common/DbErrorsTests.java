package org.altair.petgyebu_backend_renewal.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DbErrorsTests {

    @Test
    void detectsForeignKeyViolationOnlyBySqlState23503() {
        assertThat(DbErrors.isForeignKeyViolation(violation("23503"))).isTrue();
        assertThat(DbErrors.isForeignKeyViolation(new RuntimeException(violation("23503")))).isTrue();

        assertThat(DbErrors.isForeignKeyViolation(violation("23505"))).isFalse();
        assertThat(DbErrors.isForeignKeyViolation(violation("23502"))).isFalse();
        assertThat(DbErrors.isForeignKeyViolation(violation(null))).isFalse();
        assertThat(DbErrors.isForeignKeyViolation(new DataIntegrityViolationException("no cause"))).isFalse();
    }

    @Test
    void detectsUniqueViolationOnlyBySqlState23505() {
        assertThat(DbErrors.isUniqueViolation(violation("23505"))).isTrue();
        assertThat(DbErrors.isUniqueViolation(new RuntimeException(violation("23505")))).isTrue();

        assertThat(DbErrors.isUniqueViolation(violation("23503"))).isFalse();
        assertThat(DbErrors.isUniqueViolation(violation(null))).isFalse();
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("x", new SQLException("x", sqlState));
    }
}
