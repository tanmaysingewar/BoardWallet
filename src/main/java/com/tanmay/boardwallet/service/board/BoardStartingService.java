package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.BoardDetailsResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.GameTransaction;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.BoardStatus;
import com.tanmay.boardwallet.entity.constant.TransactionReason;
import com.tanmay.boardwallet.entity.constant.WalletType;
import com.tanmay.boardwallet.exception.InvalidBoardStateException;
import com.tanmay.boardwallet.exception.ResourceNotFoundException;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.GameTransactionRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import com.tanmay.boardwallet.repository.WalletRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BoardStartingService {

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final GameTransactionRepository gameTransactionRepository;
    private final BoardDetailsService boardDetailsService;

    public BoardStartingService(
            BoardRepository boardRepository,
            UserRepository userRepository,
            WalletRepository walletRepository,
            GameTransactionRepository gameTransactionRepository,
            BoardDetailsService boardDetailsService) {

        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.gameTransactionRepository = gameTransactionRepository;
        this.boardDetailsService = boardDetailsService;
    }

    @Transactional
    public BoardDetailsResponseDTO startBoard(
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
                    "Only the board creator can start the game"
            );
        }

        if (board.getStatus() != BoardStatus.WAITING) {
            throw new InvalidBoardStateException(
                    "Only a waiting board can be started"
            );
        }

        List<Wallet> playerWallets =
                walletRepository.findBoardWalletsWithUsers(
                        boardId,
                        WalletType.PLAYER
                );

        if (playerWallets.size() < 2) {
            throw new InvalidBoardStateException(
                    "At least two players are required to start the game"
            );
        }

        Wallet bankWallet = walletRepository
                .findByBoard_IdAndWalletType(boardId, WalletType.BANK)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Board is missing its bank wallet"
                        )
                );

        BigDecimal startingAmount = board.getStartingAmount();

        for (Wallet playerWallet : playerWallets) {
            playerWallet.setBalance(
                    playerWallet.getBalance().add(startingAmount)
            );

            GameTransaction transaction = new GameTransaction(
                    board,
                    bankWallet,
                    playerWallet,
                    startingAmount,
                    TransactionReason.STARTING_MONEY,
                    user
            );

            gameTransactionRepository.save(transaction);
        }

        board.setStatus(BoardStatus.ACTIVE);
        board.setStartedAt(Instant.now());

        return boardDetailsService.getBoardDetails(boardId, username);
    }
}