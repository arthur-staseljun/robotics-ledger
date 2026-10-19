package com.robotics.demo.ledger.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ledger.relay")
public record RelayProperties(@DefaultValue("100") int batchSize, @DefaultValue("10s") Duration sendTimeout) {
}
