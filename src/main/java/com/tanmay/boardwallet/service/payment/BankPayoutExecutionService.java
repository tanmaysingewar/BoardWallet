package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.dto.payment.BankPayoutRequestDTO;
import com.tanmay.boardwallet.dto.payment.BankPayoutResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.GameTransaction;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.BoardStatus;
import com.tanmay.boardwallet.entity.constant.WalletType;
import com.tanmay.boardwallet.exception.IdempotencyConflictException;
import com.tanmay.boardwallet.exception.InvalidBoardStateException;
import com.tanmay.boardwallet.exception.InvalidPaymentException;
import com.tanmay.boardwallet.exception.ResourceNotFoundException;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.GameTransactionRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import com.tanmay.boardwallet.repository.WalletRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@Validated
public class BankPayoutExecutionService {

    private static final BigDecimal MAX_BALANCE =
            new BigDecimal("99999999999999999.99");

    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final WalletRepository walletRepository;
    private final GameTransactionRepository gameTransactionRepository;
    private final PaymentRequestHasher paymentRequestHasher;

    public BankPayoutExecutionService(
            UserRepository userRepository,
            BoardRepository boardRepository,
            WalletRepository walletRepository,
            GameTransactionRepository gameTransactionRepository,
            PaymentRequestHasher paymentRequestHasher) {

        this.userRepository = userRepository;
        this.boardRepository = boardRepository;
        this.walletRepository = walletRepository;
        this.gameTransactionRepository = gameTransactionRepository;
        this.paymentRequestHasher = paymentRequestHasher;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public BankPayoutResponseDTO payoutOnce(
            @NotNull UUID boardId,
            @NotNull UUID idempotencyKey,
            @NotNull @Valid BankPayoutRequestDTO request,
            @NotBlank String username) {

        User user = findEnabledUser(username);

        String requestHash = paymentRequestHasher.hash(
                PaymentRequestHasher.Operation.BANK_PAYOUT_V1,
                boardId,
                user.getId(),
                null,
                request.getAmount(),
                request.getReason()
        );

        Optional<GameTransaction> existing = gameTransactionRepository
                .findByInitiatedBy_IdAndIdempotencyKey(
                        user.getId(),
                        idempotencyKey
                );

        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Board board = boardRepository.findByIdForPayment(boardId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Board not found")
                );

        UUID playerWalletId = walletRepository
                .findWalletIdByBoardAndUser(
                        boardId,
                        user.getId(),
                        WalletType.PLAYER
                )
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not a member of this board"
                ));

        UUID bankWalletId = walletRepository
                .findWalletIdByBoardAndType(boardId, WalletType.BANK)
                .orElseThrow(() -> new IllegalStateException(
                        "Board is missing its bank wallet"
                ));

        // Match the existing bank-payment lock order:
        // player first, then bank, regardless of payment direction.
        Wallet player = lockWallet(boardId, playerWalletId);
        Wallet bank = lockWallet(boardId, bankWalletId);

        // A duplicate request may have committed while we waited.
        existing = gameTransactionRepository
                .findByInitiatedBy_IdAndIdempotencyKey(
                        user.getId(),
                        idempotencyKey
                );

        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        if (board.getStatus() != BoardStatus.ACTIVE) {
            throw new InvalidBoardStateException(
                    "Bank payouts are allowed only on an active board"
            );
        }

        if (player.getWalletType() != WalletType.PLAYER
                || player.getUser() == null
                || !player.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException(
                    "You can claim money only for your own player wallet"
            );
        }

        if (bank.getWalletType() != WalletType.BANK) {
            throw new InvalidPaymentException(
                    "The sender must be the board's bank wallet"
            );
        }

        BigDecimal amount = request.getAmount();
        BigDecimal newBalance = player.getBalance().add(amount);

        if (newBalance.compareTo(MAX_BALANCE) > 0) {
            throw new InvalidPaymentException(
                    "Payout would exceed the maximum wallet balance"
            );
        }

        player.setBalance(newBalance);

        // The unlimited bank has no numeric balance to deduct.
        GameTransaction transaction = new GameTransaction(
                board,
                bank,
                player,
                amount,
                request.getReason(),
                user,
                idempotencyKey,
                requestHash
        );

        gameTransactionRepository.saveAndFlush(transaction);

        return mapToResponse(transaction);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true
    )
    public Optional<BankPayoutResponseDTO> findExistingPayout(
            @NotNull UUID boardId,
            @NotNull UUID idempotencyKey,
            @NotNull @Valid BankPayoutRequestDTO request,
            @NotBlank String username) {

        User user = findEnabledUser(username);

        String requestHash = paymentRequestHasher.hash(
                PaymentRequestHasher.Operation.BANK_PAYOUT_V1,
                boardId,
                user.getId(),
                null,
                request.getAmount(),
                request.getReason()
        );

        return gameTransactionRepository
                .findByInitiatedBy_IdAndIdempotencyKey(
                        user.getId(),
                        idempotencyKey
                )
                .map(transaction -> replay(transaction, requestHash));
    }

    private User findEnabledUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException(
                        "Authenticated user no longer exists"
                ));

        if (!user.getEnabled()) {
            throw new DisabledException("Account is disabled");
        }

        return user;
    }

    private Wallet lockWallet(UUID boardId, UUID walletId) {
        return walletRepository
                .findByBoardIdAndIdForUpdate(boardId, walletId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wallet not found on this board"
                ));
    }

    private BankPayoutResponseDTO replay(
            GameTransaction transaction,
            String requestHash) {

        if (!requestHash.equals(transaction.getRequestHash())) {
            throw new IdempotencyConflictException(
                    "This idempotency key was used for different payment details"
            );
        }

        return mapToResponse(transaction);
    }

    private BankPayoutResponseDTO mapToResponse(
            GameTransaction transaction) {

        BankPayoutResponseDTO response = new BankPayoutResponseDTO();

        response.setTransactionId(transaction.getId());
        response.setBoardId(transaction.getBoard().getId());
        response.setFromWalletId(transaction.getFromWallet().getId());
        response.setToWalletId(transaction.getToWallet().getId());
        response.setAmount(transaction.getAmount());
        response.setReason(transaction.getReason());
        response.setCreatedAt(transaction.getCreatedAt());

        return response;
    }
}