package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity.TransactionKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionEntity, TransactionKey> {

    List<TransactionEntity> findByAccountIdOrderBySeqIdDesc(Long accountId);
}
