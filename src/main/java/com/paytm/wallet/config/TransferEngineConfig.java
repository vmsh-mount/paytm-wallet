package com.paytm.wallet.config;

import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.transfer.ConditionalUpdateEngine;
import com.paytm.wallet.service.transfer.SelectForUpdateEngine;
import com.paytm.wallet.service.transfer.SerializableEngine;
import com.paytm.wallet.service.transfer.TransferEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Selects the active {@link TransferEngine} via {@code wallet.transfer.engine}
 * ({@code conditional-update} | {@code select-for-update} | {@code serializable}),
 * default {@code conditional-update}. All three ship in the image — flip the env
 * var and restart, no rebuild.
 */
@Configuration
public class TransferEngineConfig {

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "conditional-update", matchIfMissing = true)
    TransferEngine conditionalUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                           WalletMetrics metrics) {
        return new ConditionalUpdateEngine(jdbc, txManager, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "select-for-update")
    TransferEngine selectForUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                         WalletMetrics metrics) {
        return new SelectForUpdateEngine(jdbc, txManager, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "serializable")
    TransferEngine serializableEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                      WalletMetrics metrics,
                                      @Value("${wallet.transfer.serializable-max-retries:5}") int maxRetries) {
        return new SerializableEngine(jdbc, txManager, metrics, maxRetries);
    }
}
