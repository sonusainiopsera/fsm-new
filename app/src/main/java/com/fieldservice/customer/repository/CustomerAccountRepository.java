package com.fieldservice.customer.repository;

import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CustomerAccountRepository extends ScopedRepository<CustomerAccount, UUID> {
}
