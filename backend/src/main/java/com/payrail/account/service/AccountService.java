package com.payrail.account.service;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.common.model.PagedResponse;
import com.payrail.common.util.AmountParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        BigDecimal openingBalance = AmountParser.parseAmount("openingBalance", request.openingBalance(), true);
        String currency = request.currency().trim().toUpperCase();

        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();

        AccountRecord record = new AccountRecord(
                accountId,
                request.displayName().trim(),
                currency,
                openingBalance,
                openingBalance, // current_balance starts equal to opening_balance
                AccountStatus.ACTIVE,
                now,
                now
        );

        accountRepository.insert(record);
        log.info("Created synthetic account id={} currency={} openingBalance={}", accountId, currency, openingBalance);

        return AccountResponse.fromRecord(record);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id) {
        AccountRecord record = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        return AccountResponse.fromRecord(record);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AccountResponse> listAccounts(String status, String currency, int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int offset = boundedPage * boundedSize;

        List<AccountResponse> items = accountRepository.findAll(status, currency, offset, boundedSize)
                .stream()
                .map(AccountResponse::fromRecord)
                .toList();

        long total = accountRepository.count(status, currency);
        return PagedResponse.of(items, boundedPage, boundedSize, total);
    }
}
