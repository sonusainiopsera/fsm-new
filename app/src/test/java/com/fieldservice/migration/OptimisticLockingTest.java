package com.fieldservice.migration;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies that JPA optimistic locking is wired correctly on entities that have
 * a {@code @Version} column (work_order, assignment, stock_balance).
 *
 * <p>Criterion 4: an integration test proves a concurrent update raises an
 * optimistic lock failure.
 */
@DisplayName("Optimistic locking integration tests")
class OptimisticLockingTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private DataSource dataSource;

    /**
     * Loads a work order in one EntityManager, increments its version via JDBC
     * (simulating a concurrent update), then attempts to flush the stale entity —
     * expecting an OptimisticLockException.
     */
    @Test
    @DisplayName("Stale work_order update raises OptimisticLockException")
    void staleWorkOrderUpdate_raisesOptimisticLockException() throws Exception {
        java.util.UUID woId = TestJwtFactory.WO_A1;

        // Step 1: load entity in EM-1 and detach it (captures version = 0)
        EntityManager em1 = emf.createEntityManager();
        em1.getTransaction().begin();
        com.fieldservice.domain.workorder.WorkOrder staleWo =
                em1.find(com.fieldservice.domain.workorder.WorkOrder.class, woId);
        assertThat(staleWo).isNotNull();
        int originalVersion = staleWo.getVersion();
        em1.detach(staleWo); // detach so changes are not tracked by EM-1
        em1.getTransaction().rollback();
        em1.close();

        // Step 2: simulate concurrent update by incrementing version in the DB directly
        try (Connection conn = dataSource.getConnection()) {
            String sql = "UPDATE work_order SET version = version + 1 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, woId);
                ps.executeUpdate();
            }
        }

        // Step 3: try to update the stale entity in a new EM — must throw
        EntityManager em2 = emf.createEntityManager();
        em2.getTransaction().begin();
        staleWo.setDescription("This update uses a stale version");
        em2.merge(staleWo);

        Throwable thrown = catchThrowableOfType(
                () -> em2.flush(),
                Throwable.class);

        // Hibernate wraps as OptimisticLockException or StaleObjectStateException
        boolean isOptimisticLock = thrown instanceof OptimisticLockException
                || thrown instanceof org.hibernate.StaleObjectStateException
                || (thrown != null && thrown.getCause() instanceof OptimisticLockException)
                || (thrown != null && thrown.getCause() instanceof org.hibernate.StaleObjectStateException);

        assertThat(isOptimisticLock)
                .as("Expected OptimisticLockException or StaleObjectStateException, got: %s", thrown)
                .isTrue();

        em2.getTransaction().rollback();
        em2.close();

        // Restore DB state
        try (Connection conn = dataSource.getConnection()) {
            String sql = "UPDATE work_order SET version = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, originalVersion);
                ps.setObject(2, woId);
                ps.executeUpdate();
            }
        }
    }
}
