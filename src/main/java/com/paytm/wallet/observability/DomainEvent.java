package com.paytm.wallet.observability;

/**
 * Closed set of money-relevant events emitted to the structured log as
 * {@code "event":"<code>"}. A closed enum, not free text, so the stream is
 * queryable ({@code event="transfer.declined"}) and greppable in the burst demo.
 */
public enum DomainEvent {

    WALLET_CREATED("wallet.created"),
    TRANSFER_RECEIVED("transfer.received"),
    TRANSFER_DEBITED("transfer.debited"),
    TRANSFER_CREDITED("transfer.credited"),
    TRANSFER_COMPLETED("transfer.completed"),
    TRANSFER_DECLINED("transfer.declined"),
    TRANSFER_IDEMPOTENT_REPLAY("transfer.idempotent_replay"),
    TRANSFER_CONFLICT("transfer.conflict"),
    SERIALIZATION_RETRY("transfer.serialization_retry"),
    SERIALIZATION_EXHAUSTED("transfer.serialization_exhausted");

    private final String code;

    DomainEvent(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
