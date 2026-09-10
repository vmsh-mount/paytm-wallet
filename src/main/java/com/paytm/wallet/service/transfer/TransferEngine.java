package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;

/**
 * The core money-movement primitive. Given a validated request, atomically:
 *   - enforce no-overdraft on the source wallet,
 *   - move {@code amountPaise} from source to destination (conservation),
 *   - persist the transfer row and its idempotency key in the SAME transaction.
 *
 * <p>Three interchangeable implementations exist so we can benchmark and defend
 * the choice in code review (see docs/WRITEUP.md):
 * <ul>
 *   <li>{@link ConditionalUpdateEngine} — row-locked conditional {@code UPDATE ... WHERE balance >= amount}</li>
 *   <li>{@link SelectForUpdateEngine} — {@code SELECT ... FOR UPDATE} in sorted lock order</li>
 *   <li>{@link SerializableEngine} — SERIALIZABLE isolation with a bounded retry loop</li>
 * </ul>
 * All three must avoid deadlock when A→B and B→A run concurrently by acquiring
 * wallet locks in a deterministic order (ascending wallet id).
 */
public interface TransferEngine {

    /**
     * @param request validated transfer request
     * @return the fresh transfer (COMPLETED or DECLINED), or — if this idempotency
     *         key was already applied — the stored transfer marked {@code replayed}
     */
    TransferOutcome execute(TransferRequest request);

    /** Which of the three engines this is — for metric tags and logs. */
    Engine engineType();
}
