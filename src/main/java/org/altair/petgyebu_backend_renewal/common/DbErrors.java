package org.altair.petgyebu_backend_renewal.common;

import java.sql.SQLException;

public final class DbErrors {

    private DbErrors() {
    }

    // PostgreSQL foreign_key_violation. 다른 무결성 오류(UNIQUE·NOT NULL 등)와 구분한다.
    public static boolean isForeignKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
