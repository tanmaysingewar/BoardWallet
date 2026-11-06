package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.payment.BankPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.BankPaymentResponseDTO;
import com.tanmay.boardwallet.dto.payment.PlayerPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.PlayerPaymentResponseDTO;
import com.tanmay.boardwallet.service.payment.BankPaymentService;
import com.tanmay.boardwallet.service.payment.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/boards/{boardId}/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final BankPaymentService bankPaymentService;

    public PaymentController(PaymentService paymentService, BankPaymentService bankPaymentService) {
        this.paymentService = paymentService;
        this.bankPaymentService = bankPaymentService;
    }

    @PostMapping
    public ResponseEntity<PlayerPaymentResponseDTO> pay(
            @PathVariable("boardId") UUID boardId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody PlayerPaymentRequestDTO request,
            Authentication authentication) {

        PlayerPaymentResponseDTO response = paymentService.pay(
                boardId,
                idempotencyKey,
                request,
                authentication.getName()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/bank")
    public ResponseEntity<BankPaymentResponseDTO> payBank(
            @PathVariable("boardId") UUID boardId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody BankPaymentRequestDTO request,
            Authentication authentication) {

        BankPaymentResponseDTO response = bankPaymentService.pay(
                boardId,
                idempotencyKey,
                request,
                authentication.getName()
        );

        return ResponseEntity.ok(response);
    }
}