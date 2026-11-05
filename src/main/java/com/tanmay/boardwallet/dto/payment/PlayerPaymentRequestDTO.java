package com.tanmay.boardwallet.dto.payment;

import com.tanmay.boardwallet.entity.constant.TransactionReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public class PlayerPaymentRequestDTO {

    @NotNull(message = "Recipient wallet ID is required")
    private UUID recipientWalletId;

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

    @AssertTrue(message = "Player payment reason must be RENT or OTHER")
    public boolean isReasonValid() {
        // @NotNull handles a missing reason.
        return reason == null
                || reason == TransactionReason.RENT
                || reason == TransactionReason.OTHER;
    }

    public UUID getRecipientWalletId() {
        return recipientWalletId;
    }

    public void setRecipientWalletId(UUID recipientWalletId) {
        this.recipientWalletId = recipientWalletId;
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