package com.paytm.wallet.api;

import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /** POST /transfers — move money. Idempotent on idempotency_key. */
    @PostMapping
    public ResponseEntity<Dtos.TransferResponse> create(@Valid @RequestBody Dtos.CreateTransferRequest body) {
        throw new UnsupportedOperationException("scaffold");
    }

    /** GET /transfers/{id} — transfer status. */
    @GetMapping("/{id}")
    public Dtos.TransferResponse get(@PathVariable UUID id) {
        throw new UnsupportedOperationException("scaffold");
    }
}
