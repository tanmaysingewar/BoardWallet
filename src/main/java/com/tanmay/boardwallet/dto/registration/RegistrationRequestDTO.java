package com.tanmay.boardwallet.dto.registration;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.Length;

public class RegistrationRequestDTO {

    @NotBlank
    @Length(min = 4, max = 20)
    private String username;

    @NotBlank
    @Length(min = 8, max = 64)
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
