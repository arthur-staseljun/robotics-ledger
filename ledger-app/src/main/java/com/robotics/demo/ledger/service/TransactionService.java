package com.robotics.demo.ledger.service;


import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import com.robotics.demo.ledger.persistence.jpa.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    @Transactional(readOnly = true)
    public Page getTransactions(Long accountId, Long beforeSeqId, int limit) {
        accountService.getExistingAccount(accountId);
        Limit probe = Limit.of(limit + 1);
        List<TransactionEntity> found = beforeSeqId == null
                ? transactionRepository.findByAccountIdOrderBySeqIdDesc(accountId, probe)
                : transactionRepository.findByAccountIdAndSeqIdLessThanOrderBySeqIdDesc(accountId, beforeSeqId, probe);
        List<Transaction> items = found.stream()
                .limit(limit)
                .map(TransactionMapper::toDomain)
                .toList();
        return new Page(items, found.size() > limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransfer(Long transferId, Account accountFrom, Account accountTo, Money amount) {
        save(new Transaction(accountFrom.getId(), transferId, accountFrom.getLastSeq(), amount.negate(),
                accountFrom.getBalance()));
        save(new Transaction(accountTo.getId(), transferId, accountTo.getLastSeq(), amount, accountTo.getBalance()));
    }

    private void save(Transaction transaction) {
        transactionRepository.save(TransactionMapper.toEntity(transaction));
    }

    public record Page(List<Transaction> items, boolean hasMore) {
    }
}

