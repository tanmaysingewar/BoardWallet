package com.tanmay.boardwallet.exception;

import com.tanmay.boardwallet.dto.validation.ExceptionResponseDTO;
import com.tanmay.boardwallet.dto.validation.ValidationExceptionResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.core.AuthenticationException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ExceptionResponseDTO> handleDuplicateResource(
            DuplicateResourceException exception) {

        ExceptionResponseDTO response = new ExceptionResponseDTO(
                HttpStatus.CONFLICT.value(),
                exception.getMessage()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationExceptionResponseDTO> handleValidation(
            MethodArgumentNotValidException exception) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                )
        );

        ValidationExceptionResponseDTO response =
                new ValidationExceptionResponseDTO(
                        HttpStatus.BAD_REQUEST.value(),
                        "Validation failed",
                        fieldErrors
                );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
    @ExceptionHandler({
            BadCredentialsException.class,
            AccountStatusException.class
    })
    public ResponseEntity<ExceptionResponseDTO> handleLoginFailure(
            AuthenticationException exception) {

        ExceptionResponseDTO response = new ExceptionResponseDTO(
                HttpStatus.UNAUTHORIZED.value(),
                "Unable to log in with these credentials"
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(JoinCodeGenerationException.class)
    public ResponseEntity<ExceptionResponseDTO> handleJoinCodeGeneration(
            JoinCodeGenerationException exception) {

        ExceptionResponseDTO response = new ExceptionResponseDTO(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }
}