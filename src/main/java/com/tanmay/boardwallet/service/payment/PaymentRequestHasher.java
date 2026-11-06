package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.dto.payment.BankPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.PlayerPaymentRequestDTO;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class PaymentRequestHasher {

    public String hash(
            UUID boardId,
            UUID userId,
            PlayerPaymentRequestDTO request) {

        String normalizedAmount = request.getAmount()
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();

        String paymentDetails = String.join(
                "|",
                "PLAYER_PAYMENT_V1",
                boardId.toString(),
                userId.toString(),
                request.getRecipientWalletId().toString(),
                normalizedAmount,
                request.getReason().name()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = digest.digest(
                    paymentDetails.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    public String hashBankPayment(
            UUID boardId,
            UUID userId,
            BankPaymentRequestDTO request) {

        String normalizedAmount = request.getAmount()
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();

        String paymentDetails = String.join(
                "|",
                "BANK_PAYMENT_V1",
                boardId.toString(),
                userId.toString(),
                normalizedAmount,
                request.getReason().name()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = digest.digest(
                    paymentDetails.getBytes(StandardCharsets.UTF_8)
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