package com.tanmay.boardwallet.dto.payment;

import com.tanmay.boardwallet.entity.constant.TransactionReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class BankPaymentRequestDTO {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    @Digits(
            integer = 17,
            fraction = 2,
            message = "Amount must have at most 17 integer digits and 2 decimal places"
    )
    private BigDecimal amount;

    @NotNull(message = "Payment reason is required")
    private TransactionReason reason;

    @AssertTrue(message = "Bank payment reason must be PENALTY or OTHER")
    public boolean isReasonValid() {
        // @NotNull handles a missing reason.
        return reason == null
                || reason == TransactionReason.PENALTY
                || reason == TransactionReason.OTHER;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public TransactionReason getReason() {
        return reason;
    }

    public void setReason(TransactionReason reason) {
        this.reason = reason;
    }
}