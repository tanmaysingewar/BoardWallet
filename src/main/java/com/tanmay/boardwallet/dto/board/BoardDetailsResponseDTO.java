package com.tanmay.boardwallet.dto.board;

import com.tanmay.boardwallet.entity.constant.BoardStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class BoardDetailsResponseDTO {

    private UUID boardId;
    private String name;
    private BoardStatus status;
    private String joinCode;
    private BigDecimal startingAmount;
    private UUID createdById;
    private List<BoardPlayerResponseDTO> players;

    public BoardDetailsResponseDTO() {
    }

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

    public String getJoinCode() {
        return joinCode;
    }

    public void setJoinCode(String joinCode) {
        this.joinCode = joinCode;
    }

    public BigDecimal getStartingAmount() {
        return startingAmount;
    }

    public void setStartingAmount(BigDecimal startingAmount) {
        this.startingAmount = startingAmount;
    }

    public UUID getCreatedById() {
        return createdById;
    }

    public void setCreatedById(UUID createdById) {
        this.createdById = createdById;
    }

    public List<BoardPlayerResponseDTO> getPlayers() {
        return players;
    }

    public void setPlayers(List<BoardPlayerResponseDTO> players) {
        this.players = players;
    }
}