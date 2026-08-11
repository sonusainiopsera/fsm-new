package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassificationRegistryImplTest {

    @Mock DataClassificationRepository repository;
    @Mock DomainEventPublisher         eventPublisher;
    @InjectMocks ClassificationRegistryImpl service;

    // ---- findByEntity -----------------------------------------------------------

    @Test
    @DisplayName("findByEntity returns view when entity row exists")
    void findByEntity_returnsView_whenRowExists() throws Exception {
        DataClassificationEntity entity = buildEntity(UUID.randomUUID(), "AppUser", null,
                ClassificationTier.CONFIDENTIAL, 0);
        when(repository.findByEntityNameAndFieldNameIsNull("AppUser"))
                .thenReturn(Optional.of(entity));

        Optional<ClassificationView> result = service.findByEntity("AppUser");

        assertThat(result).isPresent();
        assertThat(result.get().entityName()).isEqualTo("AppUser");
        assertThat(result.get().tier()).isEqualTo(ClassificationTier.CONFIDENTIAL);
        assertThat(result.get().fieldName()).isNull();
    }

    @Test
    @DisplayName("findByEntity returns empty when no row")
    void findByEntity_returnsEmpty_whenNoRow() {
        when(repository.findByEntityNameAndFieldNameIsNull("Unknown"))
                .thenReturn(Optional.empty());
        assertThat(service.findByEntity("Unknown")).isEmpty();
    }

    // ---- findByEntityAndField ---------------------------------------------------

    @Test
    @DisplayName("findByEntityAndField returns view for field-level row")
    void findByEntityAndField_returnsView() throws Exception {
        DataClassificationEntity entity = buildEntity(UUID.randomUUID(), "AppUser",
                "passwordHash", ClassificationTier.RESTRICTED, 0);
        when(repository.findByEntityNameAndFieldName("AppUser", "passwordHash"))
                .thenReturn(Optional.of(entity));

        Optional<ClassificationView> result = service.findByEntityAndField("AppUser", "passwordHash");

        assertThat(result).isPresent();
        assertThat(result.get().tier()).isEqualTo(ClassificationTier.RESTRICTED);
        assertThat(result.get().fieldName()).isEqualTo("passwordHash");
    }

    // ---- findByTier -------------------------------------------------------------

    @Test
    @DisplayName("findByTier returns all matching rows")
    void findByTier_returnsAllMatchingRows() throws Exception {
        DataClassificationEntity e1 = buildEntity(UUID.randomUUID(), "WorkOrder", null,
                ClassificationTier.INTERNAL, 0);
        DataClassificationEntity e2 = buildEntity(UUID.randomUUID(), "StockLedger", null,
                ClassificationTier.INTERNAL, 0);
        when(repository.findByTier(ClassificationTier.INTERNAL)).thenReturn(List.of(e1, e2));

        List<ClassificationView> result = service.findByTier(ClassificationTier.INTERNAL);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ClassificationView::tier)
                .containsOnly(ClassificationTier.INTERNAL);
    }

    // ---- listPage ---------------------------------------------------------------

    @Test
    @DisplayName("listPage returns filtered page when tier filter is set")
    void listPage_returnsFilteredPage() throws Exception {
        DataClassificationEntity e = buildEntity(UUID.randomUUID(), "SlaPolicy", null,
                ClassificationTier.PUBLIC, 0);
        Page<DataClassificationEntity> page = new PageImpl<>(List.of(e));
        when(repository.findByTier(ClassificationTier.PUBLIC, PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<ClassificationView> result = service.listPage(
                PageRequest.of(0, 20), ClassificationTier.PUBLIC);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).tier()).isEqualTo(ClassificationTier.PUBLIC);
    }

    @Test
    @DisplayName("listPage returns all rows when tier filter is null")
    void listPage_returnsAllRows_whenNoFilter() throws Exception {
        DataClassificationEntity e = buildEntity(UUID.randomUUID(), "WorkOrder", null,
                ClassificationTier.INTERNAL, 0);
        when(repository.findAll(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(e)));

        Page<ClassificationView> result = service.listPage(PageRequest.of(0, 20), null);

        assertThat(result.getContent()).hasSize(1);
    }

    // ---- update: success --------------------------------------------------------

    @Test
    @DisplayName("update changes tier, publishes outbox event, returns updated view")
    void update_succeeds_whenVersionMatches() throws Exception {
        UUID id = UUID.randomUUID();
        DataClassificationEntity entity = buildEntity(id, "WorkOrder", null,
                ClassificationTier.INTERNAL, 0);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenReturn(entity);

        ClassificationView result = service.update(id, ClassificationTier.CONFIDENTIAL,
                "updated basis", "updated handling", 0, "user-123");

        assertThat(result.tier()).isEqualTo(ClassificationTier.CONFIDENTIAL);
        verify(eventPublisher).publish(any());
    }

    // ---- update: stale version → ConflictException ------------------------------

    @Test
    @DisplayName("update throws ConflictException when client version is stale")
    void update_throwsConflict_whenVersionMismatch() throws Exception {
        UUID id = UUID.randomUUID();
        DataClassificationEntity entity = buildEntity(id, "WorkOrder", null,
                ClassificationTier.INTERNAL, 5);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() ->
                service.update(id, ClassificationTier.PUBLIC, null, null, 3, "actor"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Stale version");
    }

    // ---- update: unknown id → NotFoundException ---------------------------------

    @Test
    @DisplayName("update throws NotFoundException when id is unknown")
    void update_throwsNotFound_whenUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.update(id, ClassificationTier.INTERNAL, null, null, 0, "actor"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- helpers ----------------------------------------------------------------

    private DataClassificationEntity buildEntity(UUID id, String entityName, String fieldName,
                                                  ClassificationTier tier, int version)
            throws Exception {
        var c = DataClassificationEntity.class.getDeclaredConstructor();
        c.setAccessible(true);
        DataClassificationEntity e = c.newInstance();
        setField(e, "id",         id);
        setField(e, "module",     "test");
        setField(e, "entityName", entityName);
        setField(e, "fieldName",  fieldName);
        setField(e, "tier",       tier);
        setField(e, "createdAt",  Instant.now());
        setField(e, "version",    version);
        return e;
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }
}
