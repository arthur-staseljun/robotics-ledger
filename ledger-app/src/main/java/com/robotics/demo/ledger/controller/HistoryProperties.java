package com.robotics.demo.ledger.controller;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ledger.history")
public record HistoryProperties(@DefaultValue("50") int defaultLimit, @DefaultValue("100") int maxLimit) {
}
