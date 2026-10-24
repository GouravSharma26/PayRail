package com.payrail.account.repository;

import com.payrail.account.domain.AccountRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    void insert(AccountRecord account);

    Optional<AccountRecord> findById(UUID id);

    List<AccountRecord> findAll(String status, String currency, int offset, int limit);

    long count(String status, String currency);

    boolean existsById(UUID id);
}
