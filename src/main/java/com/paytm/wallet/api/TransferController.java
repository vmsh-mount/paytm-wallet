package com.paytm.wallet.api;

import com.paytm.wallet.config.RequestContext;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.service.transfer.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <pre>
 *  Case                                  Code  Body
 *  ------------------------------------  ----  ----------------------------------
 *  completed (fresh)                     201   TransferResponse{status:COMPLETED}
 *  declined — insufficient funds (fresh) 201   TransferResponse{status:DECLINED}
 *  idempotent replay (same key + body)   200   stored TransferResponse
 *  same key, different body              409   ErrorResponse
 *  validation failure                    400   ErrorResponse
 *  caller not owner of `from`            403   ErrorResponse
 *  `from` / `to` wallet unknown          404   ErrorResponse
 *  unexpected                            500   ErrorResponse (no stack)
 * </pre>
 * A <b>declined</b> transfer is a successful API call reporting a business
 * outcome — not a {@code 4xx}. {@code 201} vs {@code 200} lets a client tell
 * "my write happened now" from "already processed".
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<Dtos.TransferResponse> create(@Valid @RequestBody Dtos.CreateTransferRequest body) {
        RequestContext ctx = RequestContext.current().orElseThrow();
        TransferOutcome outcome = transfers.create(
                new TransferRequest(body.from(), body.to(), body.amountPaise(), body.idempotencyKey()),
                ctx.getUserId(), ctx.getCorrelationId());

        HttpStatus code = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(code).body(Dtos.TransferResponse.of(outcome.transfer()));
    }

    /** GET /transfers/{id} — same {@link Dtos.TransferResponse} shape as POST. {@code 404} if unknown. */
    @GetMapping("/{id}")
    public Dtos.TransferResponse get(@PathVariable UUID id) {
        return Dtos.TransferResponse.of(transfers.get(id));
    }
}
