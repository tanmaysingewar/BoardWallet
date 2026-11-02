package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.CreateBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.CreateBoardResponseDTO;
import com.tanmay.boardwallet.exception.JoinCodeGenerationException;
import com.tanmay.boardwallet.repository.BoardRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class BoardService {
    private final BoardRepository boardRepository;
    private final BoardCreationService  boardCreationService;

    private static final String JOIN_CODE_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final int JOIN_CODE_LENGTH = 8;
    private static final int MAX_CREATION_ATTEMPTS = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    public BoardService(BoardRepository boardRepository, BoardCreationService boardCreationService) {
        this.boardRepository = boardRepository;
        this.boardCreationService = boardCreationService;
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

    private String generateJoinCode() {
        char[] code = new char[JOIN_CODE_LENGTH];

        for (int i = 0; i < code.length; i++) {
            int index = secureRandom.nextInt(JOIN_CODE_CHARACTERS.length());
            code[i] = JOIN_CODE_CHARACTERS.charAt(index);
        }

        return new String(code);
    }

}
