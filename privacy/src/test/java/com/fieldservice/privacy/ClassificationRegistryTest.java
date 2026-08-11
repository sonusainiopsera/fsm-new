package com.fieldservice.privacy;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.UpdateClassificationRequest;
import com.fieldservice.privacy.internal.ClassificationEntity;
import com.fieldservice.privacy.internal.ClassificationRepository;
import com.fieldservice.privacy.internal.ClassificationRegistryImpl;
import com.fieldservice.platform.api.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClassificationRegistryImpl} — no Spring context (WO-188, AC-5, AC-10).
 */
@DisplayName("ClassificationRegistry unit tests")
class ClassificationRegistryTest {

    private ClassificationRepository repository;
    private DomainEventPublisher eventPublisher;
    private ClassificationRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(ClassificationRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        registry = new ClassificationRegistryImpl(repository, eventPublisher);
    }

    @Test
    @DisplayName("findByEntity returns view when entity-level row exists")
    void findByEntity_returnsViewWhenPresent() {
        ClassificationEntity entity = makeEntity("WorkOrder", null, ClassificationTier.INTERNAL);
        when(repository.findByEntityNameAndFieldNameIsNull("WorkOrder")).thenReturn(Optional.of(entity));

        Optional<ClassificationView> result = registry.findByEntity("WorkOrder");

        assertThat(result).isPresent();
        assertThat(result.get().entityName()).isEqualTo("WorkOrder");
        assertThat(result.get().tier()).isEqualTo(ClassificationTier.INTERNAL);
        assertThat(result.get().fieldName()).isNull();
    }

    @Test
    @DisplayName("findByEntity returns empty when no entity-level row")
    void findByEntity_returnsEmptyWhenAbsent() {
        when(repository.findByEntityNameAndFieldNameIsNull("Unknown")).thenReturn(Optional.empty());

        Optional<ClassificationView> result = registry.findByEntity("Unknown");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByEntityAndField returns field-level view")
    void findByEntityAndField_returnsFieldView() {
        ClassificationEntity entity = makeEntity("AppUser", "passwordHash", ClassificationTier.RESTRICTED);
        when(repository.findByEntityNameAndFieldName("AppUser", "passwordHash"))
                .thenReturn(Optional.of(entity));

        Optional<ClassificationView> result = registry.findByEntityAndField("AppUser", "passwordHash");

        assertThat(result).isPresent();
        assertThat(result.get().fieldName()).isEqualTo("passwordHash");
        assertThat(result.get().tier()).isEqualTo(ClassificationTier.RESTRICTED);
    }

    @Test
    @DisplayName("findByEntityAndField with null fieldName delegates to entity-level lookup")
    void findByEntityAndField_nullField_delegatesToEntityLevel() {
        ClassificationEntity entity = makeEntity("Customer", null, ClassificationTier.CONFIDENTIAL);
        when(repository.findByEntityNameAndFieldNameIsNull("Customer")).thenReturn(Optional.of(entity));

        Optional<ClassificationView> result = registry.findByEntityAndField("Customer", null);

        assertThat(result).isPresent();
        assertThat(result.get().tier()).isEqualTo(ClassificationTier.CONFIDENTIAL);
    }

    @Test
    @DisplayName("findByTier returns all rows for the requested tier ordered by entity then field")
    void findByTier_returnsOrderedRows() {
        List<ClassificationEntity> restricted = List.of(
                makeEntity("AppUser", "passwordHash", ClassificationTier.RESTRICTED),
                makeEntity("RefreshToken", "tokenHash", ClassificationTier.RESTRICTED));
        when(repository.findByTierOrderByEntityNameAscFieldNameAsc(ClassificationTier.RESTRICTED))
                .thenReturn(restricted);

        List<ClassificationView> result = registry.findByTier(ClassificationTier.RESTRICTED);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ClassificationView::entityName)
                .containsExactly("AppUser", "RefreshToken");
    }

    @Test
    @DisplayName("findAll returns all rows ordered")
    void findAll_returnsAllRows() {
        List<ClassificationEntity> all = List.of(
                makeEntity("Customer", null, ClassificationTier.CONFIDENTIAL),
                makeEntity("SlaPolicy", null, ClassificationTier.PUBLIC),
                makeEntity("WorkOrder", null, ClassificationTier.INTERNAL));
        when(repository.findAllByOrderByEntityNameAscFieldNameAsc()).thenReturn(all);

        List<ClassificationView> result = registry.findAll();

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("ClassificationTier enum contains all four required values")
    void classificationTier_hasFourValues() {
        assertThat(ClassificationTier.values())
                .containsExactlyInAnyOrder(
                        ClassificationTier.PUBLIC,
                        ClassificationTier.INTERNAL,
                        ClassificationTier.CONFIDENTIAL,
                        ClassificationTier.RESTRICTED);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ClassificationEntity makeEntity(String entityName, String fieldName, ClassificationTier tier) {
        ClassificationEntity e = new ClassificationEntity("test", entityName, fieldName, tier, null, null);
        e.setId(UUID.randomUUID());
        return e;
    }
}
