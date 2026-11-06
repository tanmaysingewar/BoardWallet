package com.tanmay.boardwallet.dto.payment;

import com.tanmay.boardwallet.entity.constant.TransactionReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BankPayoutResponseDTO {

    private UUID transactionId;
    private UUID boardId;
    private UUID fromWalletId;
    private UUID toWalletId;
    private BigDecimal amount;
    private TransactionReason reason;
    private Instant createdAt;

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public void setBoardId(UUID boardId) {
        this.boardId = boardId;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public void setFromWalletId(UUID fromWalletId) {
        this.fromWalletId = fromWalletId;
    }

    public UUID getToWalletId() {
        return toWalletId;
    }

    public void setToWalletId(UUID toWalletId) {
        this.toWalletId = toWalletId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}