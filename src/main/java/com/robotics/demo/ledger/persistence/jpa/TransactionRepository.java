package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    List<TransactionEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
