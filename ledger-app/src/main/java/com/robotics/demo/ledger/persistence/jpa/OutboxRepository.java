package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {
}
