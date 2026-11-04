package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.Board;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Board> findByJoinCode(String joinCode);

    boolean existsByJoinCode(String joinCode);
}