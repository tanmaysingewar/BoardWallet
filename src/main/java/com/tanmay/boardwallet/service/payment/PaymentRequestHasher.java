package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.entity.constant.TransactionReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

@Component
public class PaymentRequestHasher {

    public enum Operation {
        PLAYER_PAYMENT_V1,
        BANK_PAYMENT_V1,
        BANK_PAYOUT_V1
    }

    public String hash(
            Operation operation,
            UUID boardId,
            UUID userId,
            UUID recipientWalletId,
            BigDecimal amount,
            TransactionReason reason) {

        Objects.requireNonNull(operation, "Operation is required");
        Objects.requireNonNull(boardId, "Board ID is required");
        Objects.requireNonNull(userId, "User ID is required");
        Objects.requireNonNull(amount, "Amount is required");
        Objects.requireNonNull(reason, "Reason is required");

        StringJoiner details = new StringJoiner("|")
                .add(operation.name())
                .add(boardId.toString())
                .add(userId.toString());

        if (operation == Operation.PLAYER_PAYMENT_V1) {
            Objects.requireNonNull(
                    recipientWalletId,
                    "Recipient wallet ID is required for player payments"
            );

            details.add(recipientWalletId.toString());
        } else if (recipientWalletId != null) {
            throw new IllegalArgumentException(
                    "Bank operations derive their recipient from the board and user"
            );
        }

        details.add(
                amount.setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString()
        );
        details.add(reason.name());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = digest.digest(
                    details.toString().getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}