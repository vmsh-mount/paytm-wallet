package com.paytm.wallet.config;

import com.paytm.wallet.service.transfer.ConditionalUpdateEngine;
import com.paytm.wallet.service.transfer.SelectForUpdateEngine;
import com.paytm.wallet.service.transfer.SerializableEngine;
import com.paytm.wallet.service.transfer.TransferEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Selects the active {@link TransferEngine} via {@code wallet.transfer.engine}
 * (values: {@code conditional-update} | {@code select-for-update} | {@code serializable}).
 * Default: {@code conditional-update}. Lets us swap and benchmark without code changes.
 */
@Configuration
public class TransferEngineConfig {

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "conditional-update", matchIfMissing = true)
    TransferEngine conditionalUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new ConditionalUpdateEngine(jdbc, txManager);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "select-for-update")
    TransferEngine selectForUpdateEngine(JdbcTemplate jdbc) {
        return new SelectForUpdateEngine(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.transfer.engine", havingValue = "serializable")
    TransferEngine serializableEngine(JdbcTemplate jdbc) {
        return new SerializableEngine(jdbc);
    }
}
