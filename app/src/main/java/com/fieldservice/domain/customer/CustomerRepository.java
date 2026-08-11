package com.fieldservice.domain.customer;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

public interface CustomerRepository extends ScopedRepository<Customer, UUID> {
}
