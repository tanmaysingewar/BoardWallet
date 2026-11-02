package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.board.CreateBoardRequestDTO;
import com.tanmay.boardwallet.dto.board.CreateBoardResponseDTO;
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
    public ResponseEntity<CreateBoardResponseDTO>  createBoard
    (@Valid  @RequestBody CreateBoardRequestDTO request, Authentication authentication) {
        CreateBoardResponseDTO responseDTO = boardService.createBoard(request, authentication.getName());

        return ResponseEntity.ok(responseDTO);
    }
}
