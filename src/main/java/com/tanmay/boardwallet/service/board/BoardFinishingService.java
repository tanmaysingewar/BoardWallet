package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.BoardDetailsResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.constant.BoardStatus;
import com.tanmay.boardwallet.exception.InvalidBoardStateException;
import com.tanmay.boardwallet.exception.ResourceNotFoundException;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class BoardFinishingService {

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final BoardDetailsService boardDetailsService;

    public BoardFinishingService(
            BoardRepository boardRepository,
            UserRepository userRepository,
            BoardDetailsService boardDetailsService) {

        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.boardDetailsService = boardDetailsService;
    }

    @Transactional
    public BoardDetailsResponseDTO finishBoard(
            UUID boardId,
            String username) {

        // Acquire the board lock before other database reads.
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Board not found")
                );

        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Authenticated user no longer exists"
                        )
                );

        if (!user.getEnabled()) {
            throw new DisabledException("Account is disabled");
        }

        if (!board.getCreatedBy().getId().equals(user.getId())) {
            throw new AccessDeniedException(
                    "Only the board creator can finish the game"
            );
        }

        if (board.getStatus() != BoardStatus.ACTIVE) {
            throw new InvalidBoardStateException(
                    "Only an active board can be finished"
            );
        }

        board.setStatus(BoardStatus.FINISHED);
        board.setFinishedAt(Instant.now());

        return boardDetailsService.getBoardDetails(boardId, username);
    }
}