package com.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payments Ledger API.
 *
 * The API accepts transfers and answers immediately with the transfer in a
 * pending state. A background worker (see {@link com.payments.worker.SettlementWorker})
 * settles each transfer by debiting the payer and crediting the payee only if the
 * payer's balance covers it at settlement time.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
