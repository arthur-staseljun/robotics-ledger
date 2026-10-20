package com.robotics.demo.ledger.model.domain;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.model.domain.Transfer.TransferStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class TransferTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    private static Account account(long id, long cents, Account.AccountStatus status) {
        Instant now = Instant.now();
        return Account.of(id, 100L + id, new Money(cents, EUR), status, 0, now, now);
    }

    private static Transfer pendingTransfer(Account from, Account to, long cents) {
        return Transfer.pending(from, to, new Money(cents, EUR));
    }

    @Test
    void pendingTakesTheOwnerAndTheAccountsFromTheAccounts() {
        Account from = account(1, 1000, Account.AccountStatus.ACTIVE);
        Account to = account(2, 0, Account.AccountStatus.ACTIVE);

        Transfer transfer = pendingTransfer(from, to, 250);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getOwnerId()).isEqualTo(101L);
        assertThat(transfer.getAccountFrom()).isEqualTo(1L);
        assertThat(transfer.getAccountTo()).isEqualTo(2L);
        assertThat(transfer.getTransferId()).isNull();
    }

    @Test
    void settlingMovesTheMoneyAndSucceeds() {
        Account from = account(1, 1000, Account.AccountStatus.ACTIVE);
        Account to = account(2, 0, Account.AccountStatus.ACTIVE);
        Transfer transfer = pendingTransfer(from, to, 250);

        transfer.settle(from, to);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(transfer.getErrorCode()).isNull();
        assertThat(from.getBalance()).isEqualTo(new Money(750, EUR));
        assertThat(to.getBalance()).isEqualTo(new Money(250, EUR));
    }

    @Test
    void insufficientFundsFailTheTransferWithTheirErrorCode() {
        Account from = account(1, 100, Account.AccountStatus.ACTIVE);
        Account to = account(2, 0, Account.AccountStatus.ACTIVE);
        Transfer transfer = pendingTransfer(from, to, 250);

        transfer.settle(from, to);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getErrorCode()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
        assertThat(from.getBalance()).isEqualTo(new Money(100, EUR));
        assertThat(to.getBalance()).isEqualTo(new Money(0, EUR));
    }

    @Test
    void aFrozenSourceFailsTheTransferAsNotActive() {
        Account from = account(1, 1000, Account.AccountStatus.FROZEN);
        Account to = account(2, 0, Account.AccountStatus.ACTIVE);
        Transfer transfer = pendingTransfer(from, to, 250);

        transfer.settle(from, to);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getErrorCode()).isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE.getErrorCode());
    }

    @Test
    void aFrozenDestinationFailsTheTransferToo() {
        Account from = account(1, 1000, Account.AccountStatus.ACTIVE);
        Account to = account(2, 0, Account.AccountStatus.FROZEN);
        Transfer transfer = pendingTransfer(from, to, 250);

        transfer.settle(from, to);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getErrorCode()).isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE.getErrorCode());
    }
}
