package com.paytm.wallet.api;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /**
     * POST /wallets — get-or-create a wallet for a user. Always {@code 200}: "get or
     * create" is one logical operation, and the caller can't tell (or care) which
     * half ran. Body {@code user_id} is the identity key; it is idempotent by nature.
     */
    @PostMapping
    public Dtos.WalletResponse getOrCreate(@Valid @RequestBody Dtos.CreateWalletRequest body) {
        Wallet wallet = wallets.getOrCreate(body.userId());
        return new Dtos.WalletResponse(wallet.id(), wallet.balancePaise());
    }

    /** GET /wallets/{id} — current balance. {@code 404} if unknown. */
    @GetMapping("/{id}")
    public Dtos.WalletResponse get(@PathVariable UUID id) {
        Wallet wallet = wallets.get(id);
        return new Dtos.WalletResponse(wallet.id(), wallet.balancePaise());
    }
}
