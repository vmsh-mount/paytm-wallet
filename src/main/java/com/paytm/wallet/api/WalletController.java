package com.paytm.wallet.api;

import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** POST /wallets — get-or-create a wallet for a user. Race-free. */
    @PostMapping
    public ResponseEntity<Dtos.WalletResponse> getOrCreate(@Valid @RequestBody Dtos.CreateWalletRequest body) {
        throw new UnsupportedOperationException("scaffold");
    }

    /** GET /wallets/{id} — current balance. */
    @GetMapping("/{id}")
    public Dtos.WalletResponse get(@PathVariable UUID id) {
        throw new UnsupportedOperationException("scaffold");
    }
}
