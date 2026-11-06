package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.dto.payment.BankPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.BankPaymentResponseDTO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class BankPaymentService {

    private static final int MAX_PAYMENT_ATTEMPTS = 3;

    private final BankPaymentExecutionService bankPaymentExecutionService;

    public BankPaymentService(
            BankPaymentExecutionService bankPaymentExecutionService) {

        this.bankPaymentExecutionService = bankPaymentExecutionService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BankPaymentResponseDTO pay(
            UUID boardId,
            UUID idempotencyKey,
            BankPaymentRequestDTO request,
            String username) {

        int attempts = 0;

        while (true) {
            attempts++;

            try {
                return bankPaymentExecutionService.payOnce(
                        boardId,
                        idempotencyKey,
                        request,
                        username
                );
            } catch (DataIntegrityViolationException exception) {
                Optional<BankPaymentResponseDTO> existing =
                        bankPaymentExecutionService.findExistingPayment(
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