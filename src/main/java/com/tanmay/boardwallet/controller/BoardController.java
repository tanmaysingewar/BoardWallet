package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.board.CreateBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.CreateBoardResponseDTO;
import com.tanmay.boardwallet.dto.board.JoinBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.JoinBoardResponseDTO;
import com.tanmay.boardwallet.service.board.BoardService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}