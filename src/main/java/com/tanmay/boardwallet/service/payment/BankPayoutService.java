package com.tanmay.boardwallet.service.payment;

import com.tanmay.boardwallet.dto.payment.BankPayoutRequestDTO;
import com.tanmay.boardwallet.dto.payment.BankPayoutResponseDTO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class BankPayoutService {

    private static final int MAX_PAYOUT_ATTEMPTS = 3;

    private final BankPayoutExecutionService bankPayoutExecutionService;

    public BankPayoutService(
            BankPayoutExecutionService bankPayoutExecutionService) {

        this.bankPayoutExecutionService = bankPayoutExecutionService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BankPayoutResponseDTO payout(
            UUID boardId,
            UUID idempotencyKey,
            BankPayoutRequestDTO request,
            String username) {

        int attempts = 0;

        while (true) {
            attempts++;

            try {
                return bankPayoutExecutionService.payoutOnce(
                        boardId,
                        idempotencyKey,
                        request,
                        username
                );
            } catch (DataIntegrityViolationException exception) {
                Optional<BankPayoutResponseDTO> existing =
                        bankPayoutExecutionService.findExistingPayout(
                                boardId,
                                idempotencyKey,
                                request,
                                username
                        );

                if (existing.isPresent()) {
                    return existing.get();
                }

                // No matching payout exists: propagate the database failure.
                throw exception;
            } catch (PessimisticLockingFailureException exception) {
                if (attempts >= MAX_PAYOUT_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }
}