package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.board.*;
import com.tanmay.boardwallet.service.board.BoardService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
}