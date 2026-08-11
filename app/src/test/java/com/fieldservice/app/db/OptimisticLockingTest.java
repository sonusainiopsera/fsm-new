package com.fieldservice.app.db;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies AC-4: optimistic locking on work_order raises a failure
 * when two concurrent updates collide on the same version.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OptimisticLockingTest extends AbstractIntegrationTest {

    private static final UUID WO_001 = UUID.fromString("bbbbbbbb-0001-0001-0001-000000000001");

    @Autowired
    private WorkOrderRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("Concurrent work_order update raises optimistic locking failure")
    void concurrent_update_raises_optimistic_lock_failure() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Load entity and capture its current version in the outer transaction
        WorkOrder[] holder = new WorkOrder[1];
        authenticate("disp-1", java.util.List.of("DISPATCHER"), null, null);
        tx.execute(status -> {
            holder[0] = repository.findById(WO_001)
                    .orElseThrow(() -> new IllegalStateException("WO_001 not found in seed data"));
            return null;
        });

        WorkOrder stale = holder[0];

        // First update: commit successfully
        tx.execute(status -> {
            authenticate("disp-1", java.util.List.of("DISPATCHER"), null, null);
            WorkOrder fresh = repository.findById(WO_001)
                    .orElseThrow();
            fresh.setTitle("Updated title");
            repository.save(fresh);
            return null;
        });

        // Second update using stale entity (same version as before first update)
        // must raise ObjectOptimisticLockingFailureException
        assertThatThrownBy(() -> tx.execute(status -> {
            authenticate("disp-1", java.util.List.of("DISPATCHER"), null, null);
            stale.setTitle("Conflicting title");
            repository.save(stale);
            return null;
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void authenticate(String subject, java.util.List<String> roles,
                               String technicianId, java.util.List<String> accountIds) {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", subject);
        claims.put("roles", roles);
        if (technicianId != null) claims.put("technician_id", technicianId);
        if (accountIds != null) claims.put("customer_account_ids", accountIds);

        var jwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("test-token")
                .headers(h -> h.put("alg", "HS256"))
                .claims(c -> c.putAll(claims))
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(900))
                .build();

        org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(jwt, java.util.List.of()));
    }
}
