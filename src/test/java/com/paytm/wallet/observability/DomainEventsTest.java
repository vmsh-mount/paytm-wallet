package com.paytm.wallet.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventsTest {

    private static final String SECRET_KEY = "client-supplied-idempotency-key-42";

    private ListAppender<ILoggingEvent> appender;
    private final Logger eventsLogger = (Logger) LoggerFactory.getLogger("com.paytm.wallet.events");

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        eventsLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        eventsLogger.detachAppender(appender);
    }

    private Map<String, Object> kv(int index) {
        return appender.list.get(index).getKeyValuePairs().stream()
                .collect(Collectors.toMap(p -> p.key, p -> p.value));
    }

    @Test
    void key_hash_is_12_hex_and_stable_and_not_the_key() {
        String h = DomainEvents.keyHash(SECRET_KEY);
        assertThat(h).hasSize(12).matches("[0-9a-f]{12}");
        assertThat(h).isEqualTo(DomainEvents.keyHash(SECRET_KEY));
        assertThat(h).isNotEqualTo(DomainEvents.keyHash(SECRET_KEY + "x"));
        assertThat(SECRET_KEY).doesNotContain(h);
    }

    @Test
    void transfer_received_carries_the_hash_never_the_raw_key() {
        DomainEvents.transferReceived(UUID.randomUUID(), UUID.randomUUID(), 500, SECRET_KEY);

        ILoggingEvent e = appender.list.get(0);
        assertThat(kv(0)).containsEntry("event", "transfer.received")
                .containsEntry("idempotency_key_hash", DomainEvents.keyHash(SECRET_KEY))
                .containsKeys("from", "to", "amount_paise");
        assertThat(kv(0)).doesNotContainKey("idempotency_key");
        assertThat(e.toString()).doesNotContain(SECRET_KEY);
    }

    @Test
    void debited_and_credited_carry_transfer_id_and_balance_after() {
        UUID tid = UUID.randomUUID();
        DomainEvents.transferDebited(tid, UUID.randomUUID(), 100, 900);
        DomainEvents.transferCredited(tid, UUID.randomUUID(), 100, 100);

        assertThat(kv(0)).containsEntry("event", "transfer.debited")
                .containsEntry("transfer_id", tid).containsEntry("from_balance_after", 900L);
        assertThat(kv(1)).containsEntry("event", "transfer.credited")
                .containsEntry("to_balance_after", 100L);
    }

    @Test
    void conflict_is_warn_level_and_only_the_hash() {
        DomainEvents.conflict(SECRET_KEY);
        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel().toString()).isEqualTo("WARN");
        assertThat(kv(0)).containsEntry("event", "transfer.conflict")
                .containsEntry("idempotency_key_hash", DomainEvents.keyHash(SECRET_KEY));
        assertThat(e.toString()).doesNotContain(SECRET_KEY);
    }

    @Test
    void no_null_field_noise() {
        DomainEvents.walletCreated(UUID.randomUUID(), "alice");
        assertThat(appender.list.get(0).getKeyValuePairs())
                .allSatisfy(p -> assertThat(p.value).isNotNull());
    }
}
