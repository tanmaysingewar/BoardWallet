package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.payment.PlayerPaymentRequestDTO;
import com.tanmay.boardwallet.dto.payment.PlayerPaymentResponseDTO;
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

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
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
}