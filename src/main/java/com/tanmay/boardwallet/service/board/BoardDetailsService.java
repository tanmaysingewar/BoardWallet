package com.tanmay.boardwallet.service.board;

import com.tanmay.boardwallet.dto.board.BoardDetailsResponseDTO;
import com.tanmay.boardwallet.dto.board.BoardPlayerResponseDTO;
import com.tanmay.boardwallet.entity.Board;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.entity.Wallet;
import com.tanmay.boardwallet.entity.constant.WalletType;
import com.tanmay.boardwallet.exception.ResourceNotFoundException;
import com.tanmay.boardwallet.repository.BoardRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import com.tanmay.boardwallet.repository.WalletRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BoardDetailsService {

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public BoardDetailsService(
            BoardRepository boardRepository,
            UserRepository userRepository,
            WalletRepository walletRepository) {

        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public BoardDetailsResponseDTO getBoardDetails(
            UUID boardId,
            String username) {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Authenticated user no longer exists"
                        )
                );

        if (!user.getEnabled()) {
            throw new DisabledException("Account is disabled");
        }

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Board not found")
                );

        walletRepository.findByBoard_IdAndUser_Id(boardId, user.getId())
                .filter(wallet ->
                        wallet.getWalletType() == WalletType.PLAYER
                )
                .orElseThrow(() ->
                        new AccessDeniedException(
                                "You are not a member of this board"
                        )
                );

        List<BoardPlayerResponseDTO> players =
                walletRepository.findBoardWalletsWithUsers(
                                boardId,
                                WalletType.PLAYER
                        )
                        .stream()
                        .map(this::mapToPlayerResponseDTO)
                        .toList();

        return mapToBoardDetailsResponseDTO(board, players);
    }

    private BoardPlayerResponseDTO mapToPlayerResponseDTO(Wallet wallet) {
        BoardPlayerResponseDTO response = new BoardPlayerResponseDTO();

        response.setUserId(wallet.getUser().getId());
        response.setUsername(wallet.getUser().getUsername());
        response.setWalletId(wallet.getId());
        response.setBalance(wallet.getBalance());

        return response;
    }

    private BoardDetailsResponseDTO mapToBoardDetailsResponseDTO(
            Board board,
            List<BoardPlayerResponseDTO> players) {

        BoardDetailsResponseDTO response = new BoardDetailsResponseDTO();

        response.setBoardId(board.getId());
        response.setName(board.getName());
        response.setStatus(board.getStatus());
        response.setJoinCode(board.getJoinCode());
        response.setStartingAmount(board.getStartingAmount());
        response.setCreatedById(board.getCreatedBy().getId());
        response.setPlayers(players);

        return response;
    }
}