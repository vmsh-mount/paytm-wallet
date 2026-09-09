package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test — column values -> record fields, no database, no mocking
 * framework. A {@link ResultSet} is faked with a {@link Proxy} over a column map
 * so this stays green on any JDK.
 */
class RowMappersTest {

    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    /** Minimal ResultSet: getX("col") returns row.get("col"); everything else is unused. */
    private static ResultSet row(Map<String, Object> row) {
        return (ResultSet) Proxy.newProxyInstance(
                RowMappersTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getObject", "getString", "getLong", "getTimestamp" -> {
                        Object v = row.get((String) args[0]);
                        if (v == null && method.getReturnType() == long.class) {
                            yield 0L;
                        }
                        yield v;
                    }
                    case "toString" -> "FakeResultSet" + row;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void wallet_columns_map_to_record() throws Exception {
        UUID id = UUID.randomUUID();
        Wallet w = RowMappers.WALLET.mapRow(row(Map.of(
                "id", id,
                "user_id", "alice",
                "balance_paise", 42_00L,
                "created_at", Timestamp.from(CREATED))), 1);

        assertThat(w).isEqualTo(new Wallet(id, "alice", 42_00L, CREATED));
    }

    @Test
    void transfer_columns_map_to_record() throws Exception {
        UUID id = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        Transfer t = RowMappers.TRANSFER.mapRow(row(Map.of(
                "id", id,
                "from_wallet_id", from,
                "to_wallet_id", to,
                "amount_paise", 150L,
                "idempotency_key", "key-1",
                "request_fingerprint", "fp-1",
                "status", "DECLINED",
                "decline_reason", "insufficient_funds",
                "created_at", Timestamp.from(CREATED))), 1);

        assertThat(t).isEqualTo(new Transfer(id, from, to, 150L, "key-1", "fp-1",
                Transfer.Status.DECLINED, "insufficient_funds", CREATED));
    }

    @Test
    void transfer_null_decline_reason_is_kept_null() throws Exception {
        java.util.HashMap<String, Object> r = new java.util.HashMap<>();
        r.put("id", UUID.randomUUID());
        r.put("from_wallet_id", UUID.randomUUID());
        r.put("to_wallet_id", UUID.randomUUID());
        r.put("amount_paise", 1L);
        r.put("idempotency_key", "k");
        r.put("request_fingerprint", "f");
        r.put("status", "COMPLETED");
        r.put("decline_reason", null);
        r.put("created_at", Timestamp.from(CREATED));

        Transfer t = RowMappers.TRANSFER.mapRow(row(r), 1);

        assertThat(t.declineReason()).isNull();
        assertThat(t.status()).isEqualTo(Transfer.Status.COMPLETED);
    }
}
