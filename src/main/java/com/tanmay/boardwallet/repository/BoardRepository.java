package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.Board;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Board> findByJoinCode(String joinCode);

    boolean existsByJoinCode(String joinCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Board b WHERE b.id = :boardId")
    Optional<Board> findByIdForUpdate(@Param("boardId") UUID boardId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT b FROM Board b WHERE b.id = :boardId")
    Optional<Board> findByIdForPayment(@Param("boardId") UUID boardId);
}