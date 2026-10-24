package com.payrail.seed;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds deterministic synthetic accounts for development and manual testing.
 * Strictly disabled in production.
 */
@Component
@ConditionalOnProperty(prefix = "payrail.seed", name = "enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    public static final UUID SEED_ACCOUNT_A_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID SEED_ACCOUNT_B_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    public static final UUID SEED_ACCOUNT_USD_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    private final AccountRepository accountRepository;

    public DevDataSeeder(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.count(null, null) > 0) {
            log.info("Database already contains accounts; skipping synthetic seed data.");
            return;
        }

        log.info("Seeding deterministic synthetic accounts for development profile...");
        Instant now = Instant.now();

        AccountRecord accountA = new AccountRecord(
                SEED_ACCOUNT_A_ID,
                "Synthetic Demo Account A",
                "INR",
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                AccountStatus.ACTIVE,
                now,
                now
        );

        AccountRecord accountB = new AccountRecord(
                SEED_ACCOUNT_B_ID,
                "Synthetic Demo Account B",
                "INR",
                new BigDecimal("500.00"),
                new BigDecimal("500.00"),
                AccountStatus.ACTIVE,
                now,
                now
        );

        AccountRecord accountUsd = new AccountRecord(
                SEED_ACCOUNT_USD_ID,
                "Synthetic Demo Account USD",
                "USD",
                new BigDecimal("2500.00"),
                new BigDecimal("2500.00"),
                AccountStatus.ACTIVE,
                now,
                now
        );

        accountRepository.insert(accountA);
        accountRepository.insert(accountB);
        accountRepository.insert(accountUsd);

        log.info("Synthetic seed accounts populated successfully: Account A (INR 1000.00), Account B (INR 500.00), Account USD (USD 2500.00).");
    }
}
