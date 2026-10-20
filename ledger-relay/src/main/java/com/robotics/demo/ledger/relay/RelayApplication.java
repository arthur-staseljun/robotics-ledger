package com.robotics.demo.ledger.relay;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RelayApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(RelayApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
