package com.tanmay.boardwallet.dto.registration;

import java.util.UUID;

public class RegistrationResponseDTO {
    private UUID id;
    private String username;
    private boolean isEnabled;
    private String message;

    public UUID getId() {
        return id;
    }
    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enable) {
        isEnabled = enable;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
