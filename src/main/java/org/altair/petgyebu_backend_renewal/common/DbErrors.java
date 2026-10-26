package org.altair.petgyebu_backend_renewal.common;

import java.sql.SQLException;

public final class DbErrors {

    private DbErrors() {
    }

    // PostgreSQL unique_violation
    public static boolean isUniqueViolation(Throwable e) {
        return hasSqlState(e, "23505");
    }

    // PostgreSQL foreign_key_violation. 다른 무결성 오류(UNIQUE·NOT NULL 등)와 구분한다.
    public static boolean isForeignKeyViolation(Throwable e) {
        return hasSqlState(e, "23503");
    }

    private static boolean hasSqlState(Throwable e, String sqlState) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
