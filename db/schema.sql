-- Current Account service domain — Postgres schema.
-- READY TO HYDRATE, NOT YET WIRED: the service runs in-memory until the
-- platform executes platform-infra/postgres/hydrate.sh (user-gated).
-- Money is BIGINT minor units throughout — never floating point.

CREATE TABLE IF NOT EXISTS account (
    account_id            VARCHAR(40)  PRIMARY KEY,
    customer_reference    VARCHAR(64)  NOT NULL,
    currency              CHAR(3)      NOT NULL,
    balance_minor         BIGINT       NOT NULL DEFAULT 0,
    overdraft_limit_minor BIGINT       NOT NULL DEFAULT 0 CHECK (overdraft_limit_minor >= 0),
    status                VARCHAR(12)  NOT NULL
        CHECK (status IN ('PENDING_KYC','ACTIVE','BLOCKED','REJECTED','CLOSED')),
    opened_at             TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- the defining invariant of a current account:
    CONSTRAINT balance_within_overdraft CHECK (balance_minor >= -overdraft_limit_minor)
);

CREATE INDEX IF NOT EXISTS idx_account_customer ON account (customer_reference);
CREATE INDEX IF NOT EXISTS idx_account_status   ON account (status);

CREATE TABLE IF NOT EXISTS account_transaction (
    transaction_id      VARCHAR(40)  PRIMARY KEY,
    account_id          VARCHAR(40)  NOT NULL REFERENCES account (account_id),
    type                VARCHAR(16)  NOT NULL
        CHECK (type IN ('DEPOSIT','WITHDRAWAL','FEE','CHEQUE_CREDIT')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor <> 0),  -- signed: credits +, debits -
    balance_after_minor BIGINT       NOT NULL,
    reference           VARCHAR(140),
    posted_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tx_account_posted ON account_transaction (account_id, posted_at DESC);
