package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.AbstractPostgresIntegrationTest;
import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.model.Transaction.TransactionType;
import com.robotics.demo.ledger.model.Transfer;
import com.robotics.demo.ledger.model.Transfer.TransferStatus;
import com.robotics.demo.ledger.service.AccountService;
import com.robotics.demo.ledger.service.TransactionService;
import com.robotics.demo.ledger.service.TransferService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
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
    private TransferService transferService;

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
    void unknownAccountIsRejectedOnLookupAndByTheTransferService() {
        assertThatThrownBy(() -> accountService.getExistingAccount(-1L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> accountService.getExistingAccountLocking(-1L)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
        assertThatThrownBy(() -> transferService.createTransfer(newAccount().getId(), -1L, eur("1.00")))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
    }

    @Test
    void storesTransactionsNewestFirst() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        transferService.createTransfer(from, to, eur("4.00"));
        transferService.createTransfer(to, from, eur("1.50"));

        List<Transaction> history = transactionService.getAllTransactions(from);

        assertThat(history).extracting(Transaction::getTransactionType)
                .containsExactly(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL);
        assertThat(history).extracting(Transaction::getBalanceAfter)
                .containsExactly(eur("7.50"), eur("6.00"));
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
    void parallelTransfersIntoOneAccountAreAllCounted() throws Exception {
        Long target = newAccount().getId();
        Long source = fundedAccount("100.00");

        int succeeded = runInParallel(100, () ->
                transferService.createTransfer(source, target, eur("1.00")).getStatus() == TransferStatus.SUCCESSFUL);

        assertThat(succeeded).isEqualTo(100);
        assertThat(balanceOf(target)).isEqualTo(10000);
        assertThat(balanceOf(source)).isZero();
        assertThat(transactionService.getAllTransactions(target)).hasSize(100);
    }

    private Long fundedAccount(String amount) {
        Instant now = Instant.now();
        return accountService.save(Account.of(null, eur(amount), now, now)).getId();
    }

    private long balanceOf(Long accountId) {
        return accountService.getExistingAccount(accountId).getBalance().amount();
    }

    @Test
    void successfulTransferMovesMoneyAndWritesTwoLinkedEntries() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();

        Transfer transfer = transferService.createTransfer(from, to, eur("25.50"));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(transfer.getErrorCode()).isNull();
        assertThat(balanceOf(from)).isEqualTo(7450);
        assertThat(balanceOf(to)).isEqualTo(2550);
        Transaction withdrawal = transactionService.getAllTransactions(from).getFirst();
        Transaction deposit = transactionService.getAllTransactions(to).getFirst();
        assertThat(withdrawal.getTransferId()).isEqualTo(transfer.getTransferId());
        assertThat(withdrawal.getTransactionType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(withdrawal.getBalanceAfter()).isEqualTo(eur("74.50"));
        assertThat(deposit.getTransferId()).isEqualTo(transfer.getTransferId());
        assertThat(deposit.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(deposit.getBalanceAfter()).isEqualTo(eur("25.50"));
        assertThat(transferService.getTransfer(transfer.getTransferId()).getStatus())
                .isEqualTo(TransferStatus.SUCCESSFUL);
    }

    @Test
    void failedTransferIsStoredWithItsErrorCodeAndMovesNothing() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();

        Transfer transfer = transferService.createTransfer(from, to, eur("10.01"));

        Transfer stored = transferService.getTransfer(transfer.getTransferId());
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
        assertThat(balanceOf(from)).isEqualTo(1000);
        assertThat(balanceOf(to)).isZero();
        assertThat(transactionService.getAllTransactions(to)).isEmpty();
        assertThat(transactionService.getAllTransactions(from)).isEmpty();
    }

    @Test
    void oppositeParallelTransfersDoNotDeadlockAndKeepTheTotal() throws Exception {
        Long first = fundedAccount("1000.00");
        Long second = fundedAccount("1000.00");
        AtomicInteger direction = new AtomicInteger();

        int succeeded = runInParallel(40, () -> {
            boolean forward = direction.getAndIncrement() % 2 == 0;
            Transfer transfer = forward
                    ? transferService.createTransfer(first, second, eur("1.00"))
                    : transferService.createTransfer(second, first, eur("1.00"));
            return transfer.getStatus() == TransferStatus.SUCCESSFUL;
        });

        assertThat(succeeded).isEqualTo(40);
        assertThat(balanceOf(first) + balanceOf(second)).isEqualTo(200000);
        assertThat(transactionService.getAllTransactions(first)).hasSize(40);
        assertThat(transactionService.getAllTransactions(second)).hasSize(40);
    }

    @Test
    void parallelTransfersFromOneAccountNeverOverdrawIt() throws Exception {
        Long from = fundedAccount("10.00");
        List<Long> targets = List.of(newAccount().getId(), newAccount().getId(), newAccount().getId());
        AtomicInteger next = new AtomicInteger();

        int succeeded = runInParallel(30, () -> {
            Long target = targets.get(next.getAndIncrement() % targets.size());
            return transferService.createTransfer(from, target, eur("1.00")).getStatus() == TransferStatus.SUCCESSFUL;
        });

        assertThat(succeeded).isEqualTo(10);
        assertThat(balanceOf(from)).isZero();
        assertThat(targets.stream().mapToLong(this::balanceOf).sum()).isEqualTo(1000);
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
