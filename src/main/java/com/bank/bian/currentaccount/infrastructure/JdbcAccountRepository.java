package com.bank.bian.currentaccount.infrastructure;

import com.bank.bian.currentaccount.domain.Account;
import com.bank.bian.currentaccount.domain.AccountRepository;
import com.bank.bian.currentaccount.domain.AccountTransaction;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Postgres adapter (profile `postgres`) over the repo-owned db/schema.sql.
 * Plain JDBC (JdbcClient) on purpose: the schema is hand-written and
 * constraint-bearing; no ORM in between. Activated by the Phase 2d gate
 * opening — the in-memory adapter remains the default profile.
 */
@Repository
@Profile("postgres")
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Account a) {
        jdbc.sql("""
                INSERT INTO account (account_id, customer_reference, currency, balance_minor,
                                     overdraft_limit_minor, status, opened_at, updated_at)
                VALUES (:id, :customer, :currency, :balance, :overdraft, :status, :opened, :updated)
                ON CONFLICT (account_id) DO UPDATE SET
                    balance_minor = EXCLUDED.balance_minor,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", a.getAccountId())
                .param("customer", a.getCustomerReference())
                .param("currency", a.getCurrency())
                .param("balance", a.getBalanceMinor())
                .param("overdraft", a.getOverdraftLimitMinor())
                .param("status", a.getStatus().name())
                .param("opened", java.sql.Timestamp.from(a.getOpenedAt()))
                .param("updated", java.sql.Timestamp.from(a.getUpdatedAt()))
                .update();
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return jdbc.sql("SELECT * FROM account WHERE account_id = :id")
                .param("id", accountId)
                .query(JdbcAccountRepository::mapAccount)
                .optional();
    }

    @Override
    public Collection<Account> findAll() {
        return jdbc.sql("SELECT * FROM account ORDER BY opened_at")
                .query(JdbcAccountRepository::mapAccount)
                .list();
    }

    @Override
    public void saveTransaction(AccountTransaction tx) {
        jdbc.sql("""
                INSERT INTO account_transaction (transaction_id, account_id, type, amount_minor,
                                                 balance_after_minor, reference, posted_at)
                VALUES (:id, :account, :type, :amount, :after, :ref, :posted)
                """)
                .param("id", tx.transactionId())
                .param("account", tx.accountId())
                .param("type", tx.type().name())
                .param("amount", tx.amountMinor())
                .param("after", tx.balanceAfterMinor())
                .param("ref", tx.reference())
                .param("posted", java.sql.Timestamp.from(tx.postedAt()))
                .update();
    }

    @Override
    public List<AccountTransaction> findTransactions(String accountId) {
        return jdbc.sql("SELECT * FROM account_transaction WHERE account_id = :id ORDER BY posted_at")
                .param("id", accountId)
                .query((rs, n) -> new AccountTransaction(
                        rs.getString("transaction_id"),
                        rs.getString("account_id"),
                        AccountTransaction.Type.valueOf(rs.getString("type")),
                        rs.getLong("amount_minor"),
                        rs.getLong("balance_after_minor"),
                        rs.getString("reference"),
                        rs.getTimestamp("posted_at").toInstant()))
                .list();
    }

    private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        Account a = Account.open(
                rs.getString("account_id"),
                rs.getString("customer_reference"),
                rs.getString("currency"),
                rs.getLong("overdraft_limit_minor"),
                rs.getTimestamp("opened_at").toInstant());
        a.setBalanceMinor(rs.getLong("balance_minor"));
        a.setStatus(Account.Status.valueOf(rs.getString("status")));
        a.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return a;
    }
}
