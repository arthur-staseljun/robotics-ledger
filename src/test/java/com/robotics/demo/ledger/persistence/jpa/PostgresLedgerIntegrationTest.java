package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.AbstractPostgresIntegrationTest;
import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.model.Transaction.TransactionType;
import com.robotics.demo.ledger.service.AccountService;
import com.robotics.demo.ledger.service.TransactionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("container")
@SpringBootTest
class PostgresLedgerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }

    private Account newAccount() {
        return accountService.createAccount();
    }

    @Test
    void savesAndLoadsAnAccount() {
        Account saved = newAccount();

        Account loaded = accountService.getExistingAccount(saved.getId());

        assertThat(saved.getId()).isNotNull();
        assertThat(loaded.getBalance()).isEqualTo(Money.zero(EUR));
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void lockingLookupOutsideATransactionIsRejected() {
        Long id = newAccount().getId();

        assertThatThrownBy(() -> accountService.getExistingAccountLocking(id))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unknownAccountIsRejectedOnLookupAndOnTransaction() {
        assertThatThrownBy(() -> accountService.getExistingAccount(-1L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
        assertThatThrownBy(() -> transactionService.createTransaction(-1L, eur("1.00")))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
    }

    @Test
    void appliesDepositAndWithdrawalAndRefusesOverdraft() {
        Long id = newAccount().getId();

        transactionService.createTransaction(id, eur("100.00"));
        transactionService.createTransaction(id, eur("-30.00"));
        assertThatThrownBy(() -> transactionService.createTransaction(id, eur("-70.01")))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS);

        assertThat(accountService.getExistingAccount(id).getBalance()).isEqualTo(eur("70.00"));
    }

    @Test
    void storesTransactionsNewestFirst() {
        Long id = newAccount().getId();
        transactionService.createTransaction(id, eur("10.00"));
        transactionService.createTransaction(id, eur("-4.00"));

        List<Transaction> history = transactionService.getAllTransactions(id);

        assertThat(history).extracting(Transaction::getTransactionType)
                .containsExactly(TransactionType.WITHDRAWAL, TransactionType.DEPOSIT);
        assertThat(history).extracting(Transaction::getBalanceAfter)
                .containsExactly(eur("6.00"), eur("10.00"));
    }

    @Test
    void rollsBackTheBalanceWhenTheTransactionFails() {
        Long id = newAccount().getId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Account account = accountService.getExistingAccountLocking(id);
            account.apply(eur("50.00"));
            accountService.save(account);
            throw new IllegalStateException("failure before the entry is saved");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(accountService.getExistingAccount(id).getBalance()).isEqualTo(Money.zero(EUR));
        assertThat(transactionService.getAllTransactions(id)).isEmpty();
    }

    @Test
    void parallelWithdrawalsNeverOverdrawTheAccount() throws Exception {
        Long id = newAccount().getId();
        transactionService.createTransaction(id, eur("20.00"));
        AtomicInteger insufficientFunds = new AtomicInteger();

        int succeeded = runInParallel(50, () -> {
            try {
                transactionService.createTransaction(id, eur("-1.00"));
                return true;
            } catch (LedgerServiceException e) {
                assertThat(e.getErrorClassification()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS);
                insufficientFunds.incrementAndGet();
                return false;
            }
        });

        assertThat(succeeded).isEqualTo(20);
        assertThat(insufficientFunds.get()).isEqualTo(30);
        assertThat(accountService.getExistingAccount(id).getBalance()).isEqualTo(Money.zero(EUR));
        assertThat(transactionService.getAllTransactions(id)).hasSize(21);
    }

    @Test
    void parallelDepositsAreAllCounted() throws Exception {
        Long id = newAccount().getId();

        runInParallel(100, () -> {
            transactionService.createTransaction(id, eur("1.00"));
            return true;
        });

        assertThat(accountService.getExistingAccount(id).getBalance()).isEqualTo(eur("100.00"));
        assertThat(transactionService.getAllTransactions(id)).hasSize(100);
    }

    private static int runInParallel(int tasks, Callable<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(task));
            }
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            return succeeded;
        } finally {
            pool.shutdown();
        }
    }
}
