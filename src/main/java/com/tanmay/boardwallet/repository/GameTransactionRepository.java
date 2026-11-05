package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.GameTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GameTransactionRepository
        extends JpaRepository<GameTransaction, UUID> {

    Optional<GameTransaction> findByInitiatedBy_IdAndIdempotencyKey(
            UUID userId,
            UUID idempotencyKey
    );
}