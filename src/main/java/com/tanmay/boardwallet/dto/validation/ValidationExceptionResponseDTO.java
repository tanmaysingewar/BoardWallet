package com.tanmay.boardwallet.dto.validation;

import java.util.Map;

public class ValidationExceptionResponseDTO extends ExceptionResponseDTO {

    private Map<String, String> fieldErrors;

    public ValidationExceptionResponseDTO(
            int statusCode,
            String message,
            Map<String, String> fieldErrors) {

        super(statusCode, message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}