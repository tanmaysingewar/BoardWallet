package com.tanmay.boardwallet.dto.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

public class JoinBoardRequestDTO {
    @NotBlank(message = "Join code is required")
    @Pattern(
            regexp = "[A-Z0-9]{8}",
            message = "Join code must contain exactly 8 letters or digits"
    )
    private String joinCode;

    public String getJoinCode() {
        return joinCode;
    }

    public void setJoinCode(String joinCode) {
        this.joinCode = joinCode == null
                ? null
                : joinCode.trim().toUpperCase(Locale.ROOT);
    }
}
