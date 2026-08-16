package com.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments")
public class PaymentsProperties {

    private final Http http = new Http();
    private final Worker worker = new Worker();
    private final Datasource datasource = new Datasource();

    public Http getHttp() {
        return http;
    }

    public Worker getWorker() {
        return worker;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public static class Http {
        private int dbPermits = 24;
        private long dbPermitTimeoutMs = 250;

        public int getDbPermits() {
            return dbPermits;
        }

        public void setDbPermits(int dbPermits) {
            this.dbPermits = dbPermits;
        }

        public long getDbPermitTimeoutMs() {
            return dbPermitTimeoutMs;
        }

        public void setDbPermitTimeoutMs(long dbPermitTimeoutMs) {
            this.dbPermitTimeoutMs = dbPermitTimeoutMs;
        }
    }

    public static class Worker {
        private int threads = 4;
        private long sweepIntervalMs = 500;

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public long getSweepIntervalMs() {
            return sweepIntervalMs;
        }

        public void setSweepIntervalMs(long sweepIntervalMs) {
            this.sweepIntervalMs = sweepIntervalMs;
        }
    }

    public static class Datasource {
        private int maximumPoolSize = 32;
        private int minimumIdle = 8;
        private long connectionTimeoutMs = 5_000;

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }
    }
}
