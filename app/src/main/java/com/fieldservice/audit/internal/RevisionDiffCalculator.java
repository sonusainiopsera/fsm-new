package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditRevisionQueryService.FieldDiff;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes field-level before/after diffs between adjacent revisions of an audited entity.
 *
 * <p>Loads the target revision and its immediate predecessor for the same entity id.
 * Produces {@link FieldDiff} entries for every declared non-transient, non-static field.
 * For the first revision (no predecessor) all fields are shown as added.
 * For a deletion revision the before snapshot is the last known state.
 */
@Component
class RevisionDiffCalculator {

    private final EntityManager entityManager;
    private final PiiMaskingPolicy masking;

    RevisionDiffCalculator(EntityManager entityManager, PiiMaskingPolicy masking) {
        this.entityManager = entityManager;
        this.masking = masking;
    }

    /**
     * Computes the field-level diff for the given revision against its predecessor.
     *
     * @param entityClass    JPA entity class corresponding to the entity type
     * @param entityId       entity UUID
     * @param revisionNumber target revision number
     * @param entityType     allow-listed entity type name (for PII masking)
     * @return list of field diffs; never null
     */
    List<FieldDiff> compute(Class<?> entityClass, Object entityId,
                             int revisionNumber, String entityType) {
        AuditReader reader = AuditReaderFactory.get(entityManager);
        List<Number> revNumbers = reader.getRevisions(entityClass, entityId);

        Object currentSnapshot = reader.find(entityClass, entityId, revisionNumber);
        Object previousSnapshot = null;

        // Find the predecessor revision
        for (int i = revNumbers.size() - 1; i >= 0; i--) {
            int r = revNumbers.get(i).intValue();
            if (r < revisionNumber) {
                previousSnapshot = reader.find(entityClass, entityId, r);
                break;
            }
        }

        return buildDiff(entityClass, entityType, previousSnapshot, currentSnapshot);
    }

    private List<FieldDiff> buildDiff(Class<?> entityClass, String entityType,
                                       Object before, Object after) {
        List<FieldDiff> diffs = new ArrayList<>();
        for (Field f : collectFields(entityClass)) {
            f.setAccessible(true);
            String name = f.getName();
            try {
                Object beforeVal = before != null ? f.get(before) : null;
                Object afterVal  = after  != null ? f.get(after)  : null;

                Object maskedBefore = masking.maskIfPii(entityType, name, beforeVal);
                Object maskedAfter  = masking.maskIfPii(entityType, name, afterVal);
                boolean masked = masking.isPii(entityType, name);

                boolean changed = !Objects.equals(beforeVal, afterVal);
                diffs.add(new FieldDiff(name, maskedBefore, maskedAfter, changed, masked));
            } catch (IllegalAccessException ignored) {}
        }
        return diffs;
    }

    private static List<Field> collectFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mod)
                        && !java.lang.reflect.Modifier.isTransient(mod)
                        && !f.isSynthetic()
                        && !f.isAnnotationPresent(
                                org.hibernate.envers.NotAudited.class)) {
                    fields.add(f);
                }
            }
            c = c.getSuperclass();
        }
        return fields;
    }
}
