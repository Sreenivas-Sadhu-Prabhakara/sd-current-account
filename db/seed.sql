-- Sample data for local exploration after hydration. Idempotent.
INSERT INTO account (account_id, customer_reference, currency, balance_minor,
                     overdraft_limit_minor, status, opened_at, updated_at)
VALUES
    ('CA-SEED-0001', 'C-1001', 'INR',  750000, 50000, 'ACTIVE',  now(), now()),
    ('CA-SEED-0002', 'C-1002', 'INR', -20000,  50000, 'ACTIVE',  now(), now()),  -- in overdraft
    ('CA-SEED-0003', 'C-1003', 'INR',  120000, 0,     'BLOCKED', now(), now())
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO account_transaction (transaction_id, account_id, type, amount_minor,
                                 balance_after_minor, reference, posted_at)
VALUES
    ('TX-SEED-0001', 'CA-SEED-0001', 'DEPOSIT',     1000000,  1000000, 'opening deposit', now() - interval '2 days'),
    ('TX-SEED-0002', 'CA-SEED-0001', 'WITHDRAWAL',  -250000,   750000, 'rent',            now() - interval '1 day'),
    ('TX-SEED-0003', 'CA-SEED-0002', 'WITHDRAWAL',   -20000,   -20000, 'groceries (od)',  now() - interval '3 hours')
ON CONFLICT (transaction_id) DO NOTHING;
