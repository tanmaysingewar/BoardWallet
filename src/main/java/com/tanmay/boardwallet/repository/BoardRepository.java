package com.tanmay.boardwallet.repository;

import com.tanmay.boardwallet.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    Optional<Board> findByJoinCode(String joinCode);

    boolean existsByJoinCode(String joinCode);
}