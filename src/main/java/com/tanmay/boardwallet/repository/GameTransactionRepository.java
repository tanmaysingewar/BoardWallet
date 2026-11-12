package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.GameTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface GameTransactionRepository
        extends JpaRepository<GameTransaction, UUID> {

    Optional<GameTransaction> findByInitiatedBy_IdAndIdempotencyKey(
            UUID userId,
            UUID idempotencyKey
    );

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN t.toWallet.id = :walletId
                              THEN t.amount ELSE 0 END), 0) AS totalReceived,
            COALESCE(SUM(CASE WHEN t.fromWallet.id = :walletId
                              THEN t.amount ELSE 0 END), 0) AS totalPaidOut
        FROM GameTransaction t
        WHERE t.board.id = :boardId
          AND (t.toWallet.id = :walletId OR t.fromWallet.id = :walletId)
        """)
    BankTotals findBankTotals(
            @Param("boardId") UUID boardId,
            @Param("walletId") UUID walletId
    );

    interface BankTotals {
        BigDecimal getTotalReceived();
        BigDecimal getTotalPaidOut();
    }

    @Query("""
        SELECT t
        FROM GameTransaction t
        WHERE t.board.id = :boardId
          AND (:walletId IS NULL
               OR t.fromWallet.id = :walletId
               OR t.toWallet.id = :walletId)
        ORDER BY t.createdAt DESC, t.id DESC
        """)
    Page<GameTransaction> findBoardTransactionHistory(
            @Param("boardId") UUID boardId,
            @Param("walletId") UUID walletId,
            Pageable pageable
    );

    Optional<GameTransaction> findByBoard_IdAndId(UUID boardId, UUID transactionId);
}