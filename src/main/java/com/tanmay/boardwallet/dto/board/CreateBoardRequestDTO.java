package com.tanmay.boardwallet.dto.board;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public class CreateBoardRequestDTO {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    @Positive
    @Digits(integer = 17, fraction = 2)
    private BigDecimal startingAmount;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getStartingAmount() {
        return startingAmount;
    }

    public void setStartingAmount(BigDecimal startingAmount) {
        this.startingAmount = startingAmount;
    }
}
