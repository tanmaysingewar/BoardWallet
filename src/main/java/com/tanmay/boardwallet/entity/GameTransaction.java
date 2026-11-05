package com.tanmay.boardwallet.entity;

import com.tanmay.boardwallet.entity.constant.TransactionReason;
import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_transaction")
public class GameTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false, updatable = false)
    private Board board;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_wallet_id", nullable = false, updatable = false)
    private Wallet fromWallet;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_wallet_id", nullable = false, updatable = false)
    private Wallet toWallet;

    @NotNull
    @Positive
    @Digits(integer = 17, fraction = 2)
    @Column(
            nullable = false,
            precision = 19,
            scale = 2,
            updatable = false
    )
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TransactionReason reason;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false, updatable = false)
    private User initiatedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected GameTransaction() {
        // Required by JPA.
    }

    public GameTransaction(
            Board board,
            Wallet fromWallet,
            Wallet toWallet,
            BigDecimal amount,
            TransactionReason reason,
            User initiatedBy) {

        this.board = board;
        this.fromWallet = fromWallet;
        this.toWallet = toWallet;
        this.amount = amount;
        this.reason = reason;
        this.initiatedBy = initiatedBy;
    }

    @PrePersist
    private void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Board getBoard() {
        return board;
    }

    public Wallet getFromWallet() {
        return fromWallet;
    }

    public Wallet getToWallet() {
        return toWallet;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionReason getReason() {
        return reason;
    }

    public User getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}