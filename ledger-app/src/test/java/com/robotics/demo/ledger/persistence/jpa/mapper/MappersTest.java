package com.robotics.demo.ledger.persistence.jpa.mapper;

import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Customer;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class MappersTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant CREATED = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-20T10:05:00Z");

    @Test
    void anAccountSurvivesTheRoundTrip() {
        Account account = Account.of(5L, 7L, new Money(1234, EUR), Account.AccountStatus.FROZEN, 9, CREATED, UPDATED);

        Account restored = AccountMapper.toDomain(AccountMapper.toEntity(account));

        assertThat(restored).usingRecursiveComparison().isEqualTo(account);
    }

    @Test
    void aCustomerSurvivesTheRoundTrip() {
        Customer customer = Customer.of(3L, CREATED);

        Customer restored = CustomerMapper.toDomain(CustomerMapper.toEntity(customer));

        assertThat(restored).usingRecursiveComparison().isEqualTo(customer);
    }

    @Test
    void aTransferSurvivesTheRoundTripAndKeepsTheColumnsOfTheAccounts() {
        Transfer transfer = Transfer.of(8L, 7L, 1L, 2L, new Money(2500, EUR), Transfer.TransferStatus.FAILED,
                "tx-ex-02", CREATED, UPDATED);

        TransferEntity entity = TransferMapper.toEntity(transfer);
        Transfer restored = TransferMapper.toDomain(entity);

        assertThat(entity.getAccountFrom()).isEqualTo(1L);
        assertThat(entity.getAccountTo()).isEqualTo(2L);
        assertThat(restored).usingRecursiveComparison().isEqualTo(transfer);
    }

    @Test
    void aTransactionKeepsItsFieldsThroughTheMapper() {
        Transaction transaction = Transaction.of(1L, 8L, 4L, Transaction.TransactionType.WITHDRAWAL,
                new Money(-2500, EUR), new Money(7500, EUR), CREATED);

        TransactionEntity entity = TransactionMapper.toEntity(transaction);
        Transaction restored = TransactionMapper.toDomain(entity);

        assertThat(entity.getAmount()).isEqualTo(-2500);
        assertThat(entity.getBalanceAfter()).isEqualTo(7500);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(restored.getAccountId()).isEqualTo(1L);
        assertThat(restored.getTransferId()).isEqualTo(8L);
        assertThat(restored.getSeqId()).isEqualTo(4L);
        assertThat(restored.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(restored.getAmount()).isEqualTo(new Money(-2500, EUR));
        assertThat(restored.getBalanceAfter()).isEqualTo(new Money(7500, EUR));
    }
}
