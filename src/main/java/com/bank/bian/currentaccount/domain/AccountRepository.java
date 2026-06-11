package com.bank.bian.currentaccount.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port. Phase 2a ships an in-memory adapter; the Postgres adapter
 * arrives when the platform hydrates the per-domain databases (db/schema.sql
 * is ready and waiting — see bian-platform/platform-infra/postgres/).
 */
public interface AccountRepository {

    void save(Account account);

    Optional<Account> findById(String accountId);

    Collection<Account> findAll();

    void saveTransaction(AccountTransaction tx);

    List<AccountTransaction> findTransactions(String accountId);
}
