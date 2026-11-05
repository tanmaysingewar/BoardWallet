package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.dto.payment.PlayerPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.PlayerPaymentResponseDTO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final int MAX_PAYMENT_ATTEMPTS = 3;

    private final PaymentExecutionService paymentExecutionService;

    public PaymentService(
            PaymentExecutionService paymentExecutionService) {

        this.paymentExecutionService = paymentExecutionService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlayerPaymentResponseDTO pay(
            UUID boardId,
            UUID idempotencyKey,
            PlayerPaymentRequestDTO request,
            String username) {

        int attempts = 0;

        while (true) {
            attempts++;

            try {
                return paymentExecutionService.payOnce(
                        boardId,
                        idempotencyKey,
                        request,
                        username
                );
            } catch (DataIntegrityViolationException exception) {
                Optional<PlayerPaymentResponseDTO> existing =
                        paymentExecutionService.findExistingPayment(
                                boardId,
                                idempotencyKey,
                                request,
                                username
                        );

                if (existing.isPresent()) {
                    return existing.get();
                }

                // An unrelated database constraint failed.
                throw exception;
            } catch (PessimisticLockingFailureException exception) {
                if (attempts >= MAX_PAYMENT_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }
}