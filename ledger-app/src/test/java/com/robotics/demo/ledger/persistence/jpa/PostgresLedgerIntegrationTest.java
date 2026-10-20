package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.AbstractPostgresIntegrationTest;
import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Transaction.TransactionType;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.domain.Transfer.TransferStatus;
import com.robotics.demo.ledger.service.AccountService;
import com.robotics.demo.ledger.service.CustomerService;
import com.robotics.demo.ledger.service.TransactionService;
import com.robotics.demo.ledger.service.TransferService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("container")
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
class PostgresLedgerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        Long ownerId = customerService.createCustomer().getCustomerId();
        return accountService.createAccount(ownerId);
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
        assertThatThrownBy(() -> completedTransfer(newAccount().getId(), -1L, eur("1.00")))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);
    }

    @Test
    void storesTransactionsNewestFirst() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        completedTransfer(from, to, eur("4.00"));
        completedTransfer(to, from, eur("1.50"));

        List<Transaction> history = history(from);

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
        assertThat(history(id)).isEmpty();
    }

    @Test
    void parallelTransfersIntoOneAccountAreAllCounted() throws Exception {
        Long target = newAccount().getId();
        Long source = fundedAccount("100.00");

        int succeeded = runInParallel(100, () ->
                completedTransfer(source, target, eur("1.00")).getStatus() == TransferStatus.SUCCESSFUL);

        assertThat(succeeded).isEqualTo(100);
        assertThat(balanceOf(target)).isEqualTo(10000);
        assertThat(balanceOf(source)).isZero();
        assertThat(history(target)).hasSize(100);
    }

    private Transfer completedTransfer(Long from, Long to, Money amount) {
        TransferService.AcceptedTransfer accepted = transferService.accept("test-client", UUID.randomUUID(), from, to, amount);
        return transferService.process(accepted.transfer().getTransferId());
    }

    private Long fundedAccount(String amount) {
        Instant now = Instant.now();
        Long ownerId = customerService.createCustomer().getCustomerId();
        return accountService.save(Account.of(null, ownerId, eur(amount), Account.AccountStatus.ACTIVE, 0, now, now))
                .getId();
    }

    private List<Transaction> history(Long accountId) {
        return transactionService.getTransactions(accountId, null, 1000).items();
    }

    private long balanceOf(Long accountId) {
        return accountService.getExistingAccount(accountId).getBalance().amount();
    }

    @Test
    void successfulTransferMovesMoneyAndWritesTwoLinkedEntries() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();

        Transfer transfer = completedTransfer(from, to, eur("25.50"));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(transfer.getErrorCode()).isNull();
        assertThat(balanceOf(from)).isEqualTo(7450);
        assertThat(balanceOf(to)).isEqualTo(2550);
        Transaction withdrawal = history(from).getFirst();
        Transaction deposit = history(to).getFirst();
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
    void sequenceNumbersOfAnAccountGrowByOnePerEntryAndFollowLastSeq() {
        Long first = fundedAccount("100.00");
        Long second = newAccount().getId();
        completedTransfer(first, second, eur("1.00"));
        completedTransfer(second, first, eur("0.50"));
        completedTransfer(first, second, eur("2.00"));

        assertThat(history(first)).extracting(Transaction::getSeqId)
                .containsExactly(3L, 2L, 1L);
        assertThat(history(second)).extracting(Transaction::getSeqId)
                .containsExactly(3L, 2L, 1L);
        assertThat(accountService.getExistingAccount(first).getLastSeq()).isEqualTo(3);
        assertThat(accountService.getExistingAccount(second).getLastSeq()).isEqualTo(3);
    }

    private int countRows(String table, String column, Long value) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, value);
    }

    @Test
    void acceptedTransferStaysPendingWithAnOutboxEventAndAKeyRecord() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        UUID key = UUID.randomUUID();

        TransferService.AcceptedTransfer accepted = transferService.accept("test-client", key, from, to, eur("1.00"));

        Long transferId = accepted.transfer().getTransferId();
        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.transfer().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(balanceOf(from)).isEqualTo(1000);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox where transfer_id = ? and event_type = 'created' and sent_at is null",
                Integer.class, transferId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select transfer_id from idempotency_keys where client_id = ? and idempotency_key = ?",
                Long.class, "test-client", key)).isEqualTo(transferId);
    }

    @Test
    void repeatedAcceptWithTheSameKeyDoesNotCreateAnotherTransfer() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        UUID key = UUID.randomUUID();

        TransferService.AcceptedTransfer first = transferService.accept("test-client", key, from, to, eur("1.00"));
        TransferService.AcceptedTransfer second = transferService.accept("test-client", key, from, to, eur("1.00"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer().getTransferId()).isEqualTo(first.transfer().getTransferId());
        assertThat(countRows("transfers", "account_from", from)).isEqualTo(1);
        assertThat(countRows("outbox", "account_id", from)).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherRequestIsRejected() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        UUID key = UUID.randomUUID();
        transferService.accept("test-client", key, from, to, eur("1.00"));

        assertThatThrownBy(() -> transferService.accept("test-client", key, from, to, eur("2.00")))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.IDEMPOTENCY_KEY_REUSED);
        assertThat(countRows("transfers", "account_from", from)).isEqualTo(1);
    }

    @Test
    void parallelRequestsWithTheSameKeyCreateExactlyOneTransfer() throws Exception {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        UUID key = UUID.randomUUID();
        List<Long> transferIds = Collections.synchronizedList(new ArrayList<>());

        int accepted = runInParallel(10, () -> {
            transferIds.add(transferService.accept("test-client", key, from, to, eur("1.00"))
                    .transfer().getTransferId());
            return true;
        });

        assertThat(accepted).isEqualTo(10);
        assertThat(transferIds).hasSize(10).containsOnly(transferIds.getFirst());
        assertThat(countRows("transfers", "account_from", from)).isEqualTo(1);
        assertThat(countRows("outbox", "account_id", from)).isEqualTo(1);
    }

    @Test
    void parallelCustomerCreationsWithTheSameKeyCreateExactlyOneCustomer() throws Exception {
        int before = jdbcTemplate.queryForObject("select count(*) from customers", Integer.class);
        UUID key = UUID.randomUUID();
        List<Long> customerIds = Collections.synchronizedList(new ArrayList<>());

        int done = runInParallel(10, () -> {
            customerIds.add(customerService.createCustomer("test-client", key).customerId());
            return true;
        });

        assertThat(done).isEqualTo(10);
        assertThat(customerIds).hasSize(10).containsOnly(customerIds.getFirst());
        assertThat(jdbcTemplate.queryForObject("select count(*) from customers", Integer.class))
                .isEqualTo(before + 1);
    }

    @Test
    void processingTheSameTransferTwiceMovesTheMoneyOnce() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        TransferService.AcceptedTransfer accepted = transferService.accept("test-client", UUID.randomUUID(), from, to, eur("4.00"));
        Long transferId = accepted.transfer().getTransferId();

        transferService.process(transferId);
        Transfer second = transferService.process(transferId);

        assertThat(second.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(balanceOf(from)).isEqualTo(600);
        assertThat(balanceOf(to)).isEqualTo(400);
        assertThat(history(from)).hasSize(1);
        assertThat(history(to)).hasSize(1);
    }

    @Test
    void transferRecordsTheOwnerOfTheSourceAccount() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();

        Transfer transfer = completedTransfer(from, to, eur("1.00"));

        assertThat(transfer.getOwnerId()).isEqualTo(accountService.getExistingAccount(from).getOwnerId());
    }

    @Test
    void frozenAccountCannotSendMoney() {
        Instant now = Instant.now();
        Long ownerId = customerService.createCustomer().getCustomerId();
        Long frozen = accountService.save(Account.of(null, ownerId, eur("10.00"), Account.AccountStatus.FROZEN, 0,
                now, now)).getId();
        Long to = newAccount().getId();

        Transfer transfer = completedTransfer(frozen, to, eur("1.00"));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getErrorCode()).isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE.getErrorCode());
        assertThat(balanceOf(frozen)).isEqualTo(1000);
    }

    @Test
    void transactionsCannotBeUpdatedOrDeleted() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        completedTransfer(from, to, eur("1.00"));

        assertThatThrownBy(() -> jdbcTemplate.update("update transactions set amount = 0 where account_id = ?", from))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from transactions where account_id = ?", from))
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsFailedTransferWithoutErrorCode() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();
        Long ownerId = accountService.getExistingAccount(from).getOwnerId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into transfers (owner_id, account_from, account_to, amount, currency, status)"
                        + " values (?, ?, ?, 100, 'EUR', 'FAILED')", ownerId, from, to))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedTransferIsStoredWithItsErrorCodeAndMovesNothing() {
        Long from = fundedAccount("10.00");
        Long to = newAccount().getId();

        Transfer transfer = completedTransfer(from, to, eur("10.01"));

        Transfer stored = transferService.getTransfer(transfer.getTransferId());
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
        assertThat(balanceOf(from)).isEqualTo(1000);
        assertThat(balanceOf(to)).isZero();
        assertThat(history(to)).isEmpty();
        assertThat(history(from)).isEmpty();
    }

    @Test
    void oppositeParallelTransfersDoNotDeadlockAndKeepTheTotal() throws Exception {
        Long first = fundedAccount("1000.00");
        Long second = fundedAccount("1000.00");
        AtomicInteger direction = new AtomicInteger();

        int succeeded = runInParallel(40, () -> {
            boolean forward = direction.getAndIncrement() % 2 == 0;
            Transfer transfer = forward
                    ? completedTransfer(first, second, eur("1.00"))
                    : completedTransfer(second, first, eur("1.00"));
            return transfer.getStatus() == TransferStatus.SUCCESSFUL;
        });

        assertThat(succeeded).isEqualTo(40);
        assertThat(balanceOf(first) + balanceOf(second)).isEqualTo(200000);
        List<Long> expectedSequence = LongStream.rangeClosed(1, 40).boxed().sorted(Comparator.reverseOrder()).toList();
        assertThat(history(first)).extracting(Transaction::getSeqId)
                .containsExactlyElementsOf(expectedSequence);
        assertThat(history(second)).extracting(Transaction::getSeqId)
                .containsExactlyElementsOf(expectedSequence);
    }

    @Test
    void parallelTransfersFromOneAccountNeverOverdrawIt() throws Exception {
        Long from = fundedAccount("10.00");
        List<Long> targets = List.of(newAccount().getId(), newAccount().getId(), newAccount().getId());
        AtomicInteger next = new AtomicInteger();

        int succeeded = runInParallel(30, () -> {
            Long target = targets.get(next.getAndIncrement() % targets.size());
            return completedTransfer(from, target, eur("1.00")).getStatus() == TransferStatus.SUCCESSFUL;
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

    @Test
    void frozenSourceAccountFailsTheTransferWithoutMovingMoney() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();
        Long transferId = transferService.accept("test-client", UUID.randomUUID(), from, to, eur("10.00"))
                .transfer().getTransferId();

        accountService.freeze(from);
        Transfer result = transferService.process(transferId);

        assertThat(accountService.getExistingAccount(from).getStatus()).isEqualTo(Account.AccountStatus.FROZEN);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE.getErrorCode());
        assertThat(balanceOf(from)).isEqualTo(10000);
        assertThat(balanceOf(to)).isZero();
    }

    @Test
    void processingStoresOneProcessedEventAndAReplayAddsNone() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();
        Long transferId = transferService.accept("test-client", UUID.randomUUID(), from, to, eur("10.00"))
                .transfer().getTransferId();

        transferService.process(transferId);
        transferService.process(transferId);

        Integer processedEvents = jdbcTemplate.queryForObject(
                "select count(*) from outbox where transfer_id = ? and event_type = 'processed' and topic = 'transfer-results'",
                Integer.class, transferId);
        assertThat(processedEvents).isEqualTo(1);
    }

    @Test
    void pagesWalkThroughTheWholeHistoryWithoutGapsOrRepeats() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();
        for (int i = 0; i < 7; i++) {
            completedTransfer(from, to, eur("1.00"));
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            TransactionService.Page page = transactionService.getTransactions(from, cursor, 3);
            page.items().forEach(transaction -> seen.add(transaction.getSeqId()));
            cursor = page.hasMore() ? page.items().getLast().getSeqId() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly(7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void aFullLastPageHasNoNextCursor() {
        Long from = fundedAccount("100.00");
        Long to = newAccount().getId();
        completedTransfer(from, to, eur("1.00"));
        completedTransfer(from, to, eur("1.00"));

        TransactionService.Page page = transactionService.getTransactions(from, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
    }
}
