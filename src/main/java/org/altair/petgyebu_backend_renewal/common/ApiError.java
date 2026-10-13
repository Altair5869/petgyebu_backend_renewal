package org.altair.petgyebu_backend_renewal.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldError> fields) {

    public record FieldError(String field, String message) {
    }
}
