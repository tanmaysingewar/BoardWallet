package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.JoinBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.JoinBoardResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.BoardStatus;
import com.tanmay.boardwallet.entity.constant.WalletType;
import com.tanmay.boardwallet.exception.InvalidBoardStateException;
import com.tanmay.boardwallet.exception.ResourceNotFoundException;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import com.tanmay.boardwallet.repository.WalletRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class BoardJoiningService {

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public BoardJoiningService(
            BoardRepository boardRepository,
            UserRepository userRepository,
            WalletRepository walletRepository) {

        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JoinBoardResponseDTO joinOnce(
            JoinBoardRequestDTO request,
            String username) {

        Board board = boardRepository.findByJoinCode(request.getJoinCode())
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

        Optional<Wallet> existingWallet =
                walletRepository.findByBoard_IdAndUser_Id(
                        board.getId(),
                        user.getId()
                );

        if (existingWallet.isPresent()) {
            return mapToJoinBoardResponseDTO(
                    board,
                    existingWallet.get()
            );
        }

        if (board.getStatus() != BoardStatus.WAITING) {
            throw new InvalidBoardStateException(
                    "Joining is allowed only while the board is waiting"
            );
        }

        Wallet wallet = new Wallet();
        wallet.setBoard(board);
        wallet.setUser(user);
        wallet.setWalletType(WalletType.PLAYER);
        wallet.setBalance(BigDecimal.ZERO);

        walletRepository.saveAndFlush(wallet);

        return mapToJoinBoardResponseDTO(board, wallet);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<JoinBoardResponseDTO> findExistingMembership(
            JoinBoardRequestDTO request,
            String username) {

        Board board = boardRepository.findByJoinCode(request.getJoinCode())
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

        return walletRepository.findByBoard_IdAndUser_Id(
                        board.getId(),
                        user.getId()
                )
                .map(wallet -> mapToJoinBoardResponseDTO(board, wallet));
    }

    private JoinBoardResponseDTO mapToJoinBoardResponseDTO(
            Board board,
            Wallet wallet) {

        JoinBoardResponseDTO response = new JoinBoardResponseDTO();
        response.setBoardId(board.getId());
        response.setBoardName(board.getName());
        response.setStatus(board.getStatus());
        response.setWalletId(wallet.getId());
        response.setBalance(wallet.getBalance());

        return response;
    }
}