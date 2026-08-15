package com.rinha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Rinha de Backend — Recriando o PIX.
 *
 * The API accepts a transfer and answers immediately with it in
 * "pending" state; a background worker (see {@link com.rinha.worker.SettlementWorker})
 * settles it — debiting the payer and crediting the payee — only if the
 * payer's balance covers it at settlement time.
 */
@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
