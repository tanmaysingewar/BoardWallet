package com.tanmay.boardwallet.dto.board;

import com.tanmay.boardwallet.entity.constant.BoardStatus;

import java.math.BigDecimal;
import java.util.UUID;

public class JoinBoardResponseDTO {

    private UUID boardId;
    private String boardName;
    private BoardStatus status;
    private UUID walletId;
    private BigDecimal balance;

    public UUID getBoardId() {
        return boardId;
    }

    public void setBoardId(UUID boardId) {
        this.boardId = boardId;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public BoardStatus getStatus() {
        return status;
    }

    public void setStatus(BoardStatus status) {
        this.status = status;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}