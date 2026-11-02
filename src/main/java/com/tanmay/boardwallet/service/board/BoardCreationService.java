package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.CreateBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.CreateBoardResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.BoardStatus;
import com.tanmay.boardwallet.entity.constant.WalletType;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import com.tanmay.boardwallet.repository.WalletRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class BoardCreationService {

    private final BoardRepository boardRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public BoardCreationService(
            BoardRepository boardRepository,
            WalletRepository walletRepository,
            UserRepository userRepository) {

        this.boardRepository = boardRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateBoardResponseDTO createOnce(
            CreateBoardRequestDTO request,
            String username,
            String joinCode) {

        User creator = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found")
                );

        if (!creator.getEnabled()) {
            throw new DisabledException("Account is disabled");
        }

        Board board = new Board();
        board.setName(request.getName());
        board.setStartingAmount(request.getStartingAmount());
        board.setCreatedBy(creator);
        board.setJoinCode(joinCode);
        board.setStatus(BoardStatus.WAITING);

        // Detect board constraint failures before creating the wallets.
        // Flushing does not commit this transaction.
        boardRepository.saveAndFlush(board);

        Wallet bankWallet = new Wallet();
        bankWallet.setBoard(board);
        bankWallet.setWalletType(WalletType.BANK);
        // User and balance remain null for the unlimited bank.

        Wallet creatorWallet = new Wallet();
        creatorWallet.setBoard(board);
        creatorWallet.setUser(creator);
        creatorWallet.setWalletType(WalletType.PLAYER);
        creatorWallet.setBalance(BigDecimal.ZERO);

        walletRepository.save(bankWallet);
        walletRepository.save(creatorWallet);

        return mapToCreateBoardResponseDTO(board);
    }

    private CreateBoardResponseDTO mapToCreateBoardResponseDTO(Board board) {
        CreateBoardResponseDTO response = new CreateBoardResponseDTO();
        response.setId(board.getId());
        response.setName(board.getName());
        response.setJoinCode(board.getJoinCode());
        response.setStartingAmount(board.getStartingAmount());
        response.setStatus(board.getStatus());
        response.setCreatedById(board.getCreatedBy().getId());
        response.setCreatedAt(board.getCreatedAt());

        return response;
    }
}