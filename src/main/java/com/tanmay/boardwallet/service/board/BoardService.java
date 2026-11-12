package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.*;
import com.tanmay.boardwallet.exception.JoinCodeGenerationException;
import com.tanmay.boardwallet.repository.BoardRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardCreationService boardCreationService;
    private final BoardJoiningService boardJoiningService;
    private final BoardDetailsService boardDetailsService;
    private final BoardStartingService boardStartingService;
    private final BoardFinishingService boardFinishingService;

    private static final String JOIN_CODE_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final int JOIN_CODE_LENGTH = 8;
    private static final int MAX_CREATION_ATTEMPTS = 5;
    private static final int MAX_JOIN_ATTEMPTS = 3;

    private final SecureRandom secureRandom = new SecureRandom();

    public BoardService(
            BoardRepository boardRepository,
            BoardCreationService boardCreationService,
            BoardJoiningService boardJoiningService,
            BoardDetailsService boardDetailsService,
            BoardStartingService boardStartingService,
            BoardFinishingService boardFinishingService) {

        this.boardRepository = boardRepository;
        this.boardCreationService = boardCreationService;
        this.boardJoiningService = boardJoiningService;
        this.boardDetailsService = boardDetailsService;
        this.boardStartingService = boardStartingService;
        this.boardFinishingService = boardFinishingService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreateBoardResponseDTO createBoard(
            CreateBoardRequestDTO request,
            String username) {

        DataIntegrityViolationException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_CREATION_ATTEMPTS; attempt++) {
            String joinCode = generateJoinCode();

            try {
                return boardCreationService.createOnce(
                        request, username, joinCode
                );
            } catch (DataIntegrityViolationException exception) {
                // The separate service's transaction has already rolled back.
                if (!boardRepository.existsByJoinCode(joinCode)) {
                    throw exception;
                }

                lastFailure = exception;
            }
        }

        throw new JoinCodeGenerationException(
                "Unable to allocate a join code. Please try again.",
                lastFailure
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JoinBoardResponseDTO joinBoard(
            JoinBoardRequestDTO request,
            String username) {

        for (int attempt = 1; true; attempt++) {
            try {
                try {
                    return boardJoiningService.joinOnce(request, username);
                } catch (DataIntegrityViolationException exception) {
                    // joinOnce has rolled back before recovery starts.
                    return boardJoiningService
                            .findExistingMembership(request, username)
                            .orElseThrow(() -> exception);
                }
            } catch (PessimisticLockingFailureException exception) {
                // Retry with a fresh transaction after a lock failure.
                if (attempt >= MAX_JOIN_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }

    public BoardDetailsResponseDTO getBoardDetails(
            UUID boardId,
            String username) {

        return boardDetailsService.getBoardDetails(boardId, username);
    }

    public BoardDetailsResponseDTO startBoard(
            UUID boardId,
            String username) {

        return boardStartingService.startBoard(boardId, username);
    }

    public BoardDetailsResponseDTO finishBoard(
            UUID boardId,
            String username) {

        return boardFinishingService.finishBoard(boardId, username);
    }

    private String generateJoinCode() {
        char[] code = new char[JOIN_CODE_LENGTH];

        for (int i = 0; i < code.length; i++) {
            int index = secureRandom.nextInt(JOIN_CODE_CHARACTERS.length());
            code[i] = JOIN_CODE_CHARACTERS.charAt(index);
        }

        return new String(code);
    }

    public List<BoardSummaryResponseDTO> listMyBoards(String username) {
        return boardDetailsService.listMyBoards(username);
    }

    public BoardPlayerResponseDTO getMyWallet(UUID boardId, String username) {
        return boardDetailsService.getMyWallet(boardId, username);
    }

    public BankDetailsResponseDTO getBankDetails(UUID boardId, String username) {
        return boardDetailsService.getBankDetails(boardId, username);
    }
}