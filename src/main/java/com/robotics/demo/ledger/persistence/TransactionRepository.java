package com.robotics.demo.ledger.persistence;

import com.robotics.demo.ledger.model.Transaction;

import java.util.List;

public interface TransactionRepository {

    Transaction save(Transaction transaction);

    List<Transaction> findAll(Long accountId);
}
