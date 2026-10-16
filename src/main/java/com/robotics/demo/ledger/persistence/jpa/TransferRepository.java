package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<TransferEntity, Long> {
}
