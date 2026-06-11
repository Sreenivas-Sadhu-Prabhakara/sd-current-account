package com.bank.bian.currentaccount.infrastructure;

import com.bank.bian.currentaccount.domain.Account;
import com.bank.bian.currentaccount.domain.AccountRepository;
import com.bank.bian.currentaccount.domain.AccountTransaction;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 2a adapter. Atomicity across balance update + transaction append is
 * handled by the service layer's per-account locking; once the Postgres
 * adapter lands this responsibility moves to database transactions.
 */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<AccountTransaction>> transactions = new ConcurrentHashMap<>();

    @Override
    public void save(Account account) {
        accounts.put(account.getAccountId(), account);
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public Collection<Account> findAll() {
        return accounts.values();
    }

    @Override
    public void saveTransaction(AccountTransaction tx) {
        transactions.computeIfAbsent(tx.accountId(), k -> new CopyOnWriteArrayList<>()).add(tx);
    }

    @Override
    public List<AccountTransaction> findTransactions(String accountId) {
        return List.copyOf(transactions.getOrDefault(accountId, List.of()));
    }
}
