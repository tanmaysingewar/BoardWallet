package com.tanmay.boardwallet.dto.board;

import java.math.BigDecimal;
import java.util.UUID;

public class BankDetailsResponseDTO {

    private UUID walletId;
    private boolean unlimitedFunds;
    private BigDecimal totalReceived;
    private BigDecimal totalPaidOut;

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public boolean isUnlimitedFunds() {
        return unlimitedFunds;
    }

    public void setUnlimitedFunds(boolean unlimitedFunds) {
        this.unlimitedFunds = unlimitedFunds;
    }

    public BigDecimal getTotalReceived() {
        return totalReceived;
    }

    public void setTotalReceived(BigDecimal totalReceived) {
        this.totalReceived = totalReceived;
    }

    public BigDecimal getTotalPaidOut() {
        return totalPaidOut;
    }

    public void setTotalPaidOut(BigDecimal totalPaidOut) {
        this.totalPaidOut = totalPaidOut;
    }
}