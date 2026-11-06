package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.WalletType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByBoard_IdAndUser_Id(UUID boardId, UUID userId);
    Optional<Wallet> findByBoard_IdAndWalletType(UUID boardId, WalletType walletType);
    List<Wallet> findByBoard_Id(UUID boardId);

    @Query("""
            SELECT w
            FROM Wallet w
            JOIN FETCH w.user
            WHERE w.board.id = :boardId
              AND w.walletType = :walletType
            ORDER BY w.createdAt ASC, w.id ASC
            """)
    List<Wallet> findBoardWalletsWithUsers(
            @Param("boardId") UUID boardId,
            @Param("walletType") WalletType walletType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT w
        FROM Wallet w
        WHERE w.board.id = :boardId
          AND w.id = :walletId
        """)
    Optional<Wallet> findByBoardIdAndIdForUpdate(
            @Param("boardId") UUID boardId,
            @Param("walletId") UUID walletId
    );

    @Query("""
        SELECT w.id
        FROM Wallet w
        WHERE w.board.id = :boardId
          AND w.user.id = :userId
          AND w.walletType = :walletType
        """)
    Optional<UUID> findWalletIdByBoardAndUser(
            @Param("boardId") UUID boardId,
            @Param("userId") UUID userId,
            @Param("walletType") WalletType walletType
    );

    @Query("""
        SELECT w.id
        FROM Wallet w
        WHERE w.board.id = :boardId
          AND w.walletType = :walletType
        """)
    Optional<UUID> findWalletIdByBoardAndType(
            @Param("boardId") UUID boardId,
            @Param("walletType") WalletType walletType
    );
}
