package com.tanmay.boardwallet.dto.board;

import com.tanmay.boardwallet.entity.constant.BoardStatus;

import java.util.UUID;

public class BoardSummaryResponseDTO {

    private UUID boardId;
    private String name;
    private BoardStatus status;

    public UUID getBoardId() {
        return boardId;
    }

    public void setBoardId(UUID boardId) {
        this.boardId = boardId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BoardStatus getStatus() {
        return status;
    }

    public void setStatus(BoardStatus status) {
        this.status = status;
    }
}