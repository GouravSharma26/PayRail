package com.payrail;

import com.payrail.config.PayRailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * PayRail - Synthetic Payment Processing and Ledger Simulator.
 * Educational portfolio simulator strictly operating with fictional data.
 */
@SpringBootApplication
@EnableConfigurationProperties(PayRailProperties.class)
public class PayRailApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayRailApplication.class, args);
    }
}
