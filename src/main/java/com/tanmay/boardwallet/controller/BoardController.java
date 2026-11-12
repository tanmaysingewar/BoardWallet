package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.board.*;
import com.tanmay.boardwallet.dto.payment.TransactionHistoryResponseDTO;
import com.tanmay.boardwallet.service.board.BoardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @PostMapping
    public ResponseEntity<CreateBoardResponseDTO> createBoard(
            @Valid @RequestBody CreateBoardRequestDTO request,
            Authentication authentication) {

        CreateBoardResponseDTO response =
                boardService.createBoard(request, authentication.getName());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/join")
    public ResponseEntity<JoinBoardResponseDTO> joinBoard(
            @Valid @RequestBody JoinBoardRequestDTO request,
            Authentication authentication) {

        JoinBoardResponseDTO response =
                boardService.joinBoard(request, authentication.getName());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{boardId}")
    public ResponseEntity<BoardDetailsResponseDTO> getBoardDetails(
            @PathVariable("boardId") UUID boardId,
            Authentication authentication) {

        BoardDetailsResponseDTO response =
                boardService.getBoardDetails(
                        boardId,
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{boardId}/start")
    public ResponseEntity<BoardDetailsResponseDTO> startBoard(
            @PathVariable("boardId") UUID boardId,
            Authentication authentication) {

        BoardDetailsResponseDTO response =
                boardService.startBoard(
                        boardId,
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{boardId}/finish")
    public ResponseEntity<BoardDetailsResponseDTO> finishBoard(
            @PathVariable("boardId") UUID boardId,
            Authentication authentication) {

        BoardDetailsResponseDTO response =
                boardService.finishBoard(
                        boardId,
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<BoardSummaryResponseDTO>> listMyBoards(
            Authentication authentication) {

        return ResponseEntity.ok(
                boardService.listMyBoards(authentication.getName())
        );
    }

    @GetMapping("/{boardId}/wallets/me")
    public ResponseEntity<BoardPlayerResponseDTO> getMyWallet(
            @PathVariable("boardId") UUID boardId,
            Authentication authentication) {

        return ResponseEntity.ok(
                boardService.getMyWallet(boardId, authentication.getName())
        );
    }

    @GetMapping("/{boardId}/bank")
    public ResponseEntity<BankDetailsResponseDTO> getBankDetails(
            @PathVariable("boardId") UUID boardId,
            Authentication authentication) {

        return ResponseEntity.ok(
                boardService.getBankDetails(boardId, authentication.getName())
        );
    }

    @GetMapping("/{boardId}/transactions")
    public ResponseEntity<PagedModel<TransactionHistoryResponseDTO>> getTransactionHistory(
            @PathVariable("boardId") UUID boardId,
            @RequestParam(name = "walletId", required = false) UUID walletId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {

        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Page offset is too large"
            );
        }

        Page<TransactionHistoryResponseDTO> transactions =
                boardService.getTransactionHistory(
                        boardId,
                        walletId,
                        PageRequest.of(page, size),
                        authentication.getName()
                );

        return ResponseEntity.ok(new PagedModel<>(transactions));
    }

    @GetMapping("/{boardId}/transactions/{transactionId}")
    public ResponseEntity<TransactionHistoryResponseDTO> getTransactionDetails(
            @PathVariable("boardId") UUID boardId,
            @PathVariable("transactionId") UUID transactionId,
            Authentication authentication) {

        return ResponseEntity.ok(
                boardService.getTransactionDetails(
                        boardId, transactionId, authentication.getName()
                )
        );
    }
}