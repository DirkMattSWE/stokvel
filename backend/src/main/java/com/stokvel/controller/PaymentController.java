package com.stokvel.controller;

import com.stokvel.dto.PaymentResponse;
import com.stokvel.dto.RecordPaymentRequest;
import com.stokvel.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin by design: unpacks the request, calls the service, maps to a DTO. No rule is
 * decided here — not which cycle the money lands in, not what gets settled first.
 *
 * One endpoint, POST only. There is no PUT and no DELETE because there is no
 * updatePayment and no deletePayment to call: if no edit path exists, there is
 * structurally nothing to tamper with.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> recordPayment(@RequestBody RecordPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(
                paymentService.recordPayment(request.memberId(), request.amount())));
    }
}
