package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
}
