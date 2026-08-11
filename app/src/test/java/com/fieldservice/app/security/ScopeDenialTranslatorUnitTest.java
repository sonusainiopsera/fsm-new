package com.fieldservice.app.security;

import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopeDenialTranslator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ScopeDenialTranslator}: verifies the 403-versus-404 rule,
 * Micrometer counter increments, and deny-by-default behaviour for unknown roles.
 *
 * <p>Uses {@link SimpleMeterRegistry} so no Spring context is required.
 */
class ScopeDenialTranslatorUnitTest {

    private MeterRegistry     registry;
    private ScopeDenialTranslator translator;

    private static final UUID USER_ID   = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID RESOURCE  = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        registry    = new SimpleMeterRegistry();
        translator  = new ScopeDenialTranslator(registry);
    }

    // -------------------------------------------------------------------------
    // 403 path (non-CUSTOMER roles)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-role denial → 403 FORBIDDEN")
    class CrossRoleDenial {

        @Test
        @DisplayName("TECHNICIAN cross-role probe throws ScopedAccessDeniedException (→ 403)")
        void technician_throws_scoped_access_denied() {
            AccessScope techScope = new AccessScope(USER_ID, Set.of("TECHNICIAN"),
                    UUID.randomUUID(), null);

            assertThatThrownBy(() -> translator.deny(techScope, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("DISPATCHER cross-probe throws ScopedAccessDeniedException (→ 403)")
        void dispatcher_throws_scoped_access_denied() {
            AccessScope dispScope = new AccessScope(USER_ID, Set.of("DISPATCHER"), null, null);

            assertThatThrownBy(() -> translator.deny(dispScope, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("MANAGER throws ScopedAccessDeniedException (→ 403)")
        void manager_throws_scoped_access_denied() {
            AccessScope mgr = new AccessScope(USER_ID, Set.of("MANAGER"), null, null);

            assertThatThrownBy(() -> translator.deny(mgr, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("Unknown role throws ScopedAccessDeniedException (deny-by-default)")
        void unknown_role_throws_scoped_access_denied() {
            AccessScope unknown = new AccessScope(USER_ID, Set.of("ROBOT"), null, null);

            assertThatThrownBy(() -> translator.deny(unknown, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("Empty roles throw ScopedAccessDeniedException (deny-by-default)")
        void empty_roles_throws_scoped_access_denied() {
            AccessScope empty = new AccessScope(USER_ID, Set.of(), null, null);

            assertThatThrownBy(() -> translator.deny(empty, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("TECHNICIAN denial increments counter with outcome=FORBIDDEN")
        void technician_denial_increments_counter_forbidden() {
            AccessScope techScope = new AccessScope(USER_ID, Set.of("TECHNICIAN"),
                    UUID.randomUUID(), null);

            assertThatThrownBy(() -> translator.deny(techScope, "work_order", RESOURCE))
                    .isInstanceOf(ScopedAccessDeniedException.class);

            double count = registry
                    .counter(ScopeDenialTranslator.COUNTER_NAME,
                            "role", "TECHNICIAN", "resource", "work_order", "outcome", "FORBIDDEN")
                    .count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------------
    // 404 path (CUSTOMER role)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-account customer denial → 404 NOT_FOUND")
    class CrossAccountDenial {

        @Test
        @DisplayName("CUSTOMER cross-account probe throws NotFoundException (→ 404)")
        void customer_throws_not_found() {
            AccessScope custScope = new AccessScope(USER_ID, Set.of("CUSTOMER"), null,
                    Set.of(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")));

            assertThatThrownBy(() -> translator.deny(custScope, "work_order", RESOURCE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("CUSTOMER denial with null resource id does not NPE")
        void customer_denial_null_resource_id_does_not_npe() {
            AccessScope custScope = new AccessScope(USER_ID, Set.of("CUSTOMER"), null, Set.of());

            assertThatThrownBy(() -> translator.deny(custScope, "work_order", null))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("CUSTOMER denial increments counter with outcome=NOT_FOUND")
        void customer_denial_increments_counter_not_found() {
            AccessScope custScope = new AccessScope(USER_ID, Set.of("CUSTOMER"), null,
                    Set.of(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")));

            assertThatThrownBy(() -> translator.deny(custScope, "work_order", RESOURCE))
                    .isInstanceOf(NotFoundException.class);

            double count = registry
                    .counter(ScopeDenialTranslator.COUNTER_NAME,
                            "role", "CUSTOMER", "resource", "work_order", "outcome", "NOT_FOUND")
                    .count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------------
    // resourceType in thrown exception
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ScopedAccessDeniedException carries the resourceType for audit logging")
    void scoped_exception_carries_resource_type() {
        AccessScope techScope = new AccessScope(USER_ID, Set.of("TECHNICIAN"),
                UUID.randomUUID(), null);

        try {
            translator.deny(techScope, "site", RESOURCE);
        } catch (ScopedAccessDeniedException ex) {
            assertThat(ex.resourceType()).isEqualTo("site");
        }
    }

    @Test
    @DisplayName("Counter increments once per deny call, not multiple times")
    void counter_increments_exactly_once() {
        AccessScope techScope = new AccessScope(USER_ID, Set.of("TECHNICIAN"),
                UUID.randomUUID(), null);

        assertThatThrownBy(() -> translator.deny(techScope, "work_order", RESOURCE)).isNotNull();
        assertThatThrownBy(() -> translator.deny(techScope, "work_order", RESOURCE)).isNotNull();

        double count = registry
                .counter(ScopeDenialTranslator.COUNTER_NAME,
                        "role", "TECHNICIAN", "resource", "work_order", "outcome", "FORBIDDEN")
                .count();
        assertThat(count).isEqualTo(2.0);
    }
}
