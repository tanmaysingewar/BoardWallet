package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.WalletType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByBoard_IdAndUser_Id(UUID boardId, UUID userId);
    Optional<Wallet> findByBoard_IdAndWalletType(UUID boardId, WalletType walletType);
    List<Wallet> findByBoard_Id(UUID boardId);
}
