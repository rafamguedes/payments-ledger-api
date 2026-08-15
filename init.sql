-- Schema for the Rinha de Backend PIX challenge.
-- Money is always an integer number of cents (BIGINT) — never a float.

CREATE TABLE IF NOT EXISTS accounts (
    id      VARCHAR(64) PRIMARY KEY,
    balance BIGINT NOT NULL CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS transfers (
    id               UUID PRIMARY KEY,
    payer_id         VARCHAR(64) NOT NULL REFERENCES accounts (id),
    payee_id         VARCHAR(64) NOT NULL REFERENCES accounts (id),
    amount           BIGINT NOT NULL CHECK (amount > 0),
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    status           VARCHAR(16) NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending', 'completed', 'failed')),
    failure_reason   VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The worker polls/sweeps pending transfers.
CREATE INDEX IF NOT EXISTS idx_transfers_status_created
    ON transfers (status, created_at);

-- The statement endpoint looks up completed transfers per account,
-- newest first, on both sides of the transfer.
CREATE INDEX IF NOT EXISTS idx_transfers_payer_completed
    ON transfers (payer_id, created_at DESC) WHERE status = 'completed';
CREATE INDEX IF NOT EXISTS idx_transfers_payee_completed
    ON transfers (payee_id, created_at DESC) WHERE status = 'completed';
