package com.fieldservice.identity.privacy;

import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.customer.CustomerRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rectifier for customer identity fields.
 *
 * <p>Supports the {@code CUSTOMER} subject type. Applies corrections to
 * the {@link Customer} entity via direct field update, triggering Envers auditing
 * in the same transaction.
 */
@Component
public class IdentitySubjectDataRectifier implements SubjectDataRectifier {

    /** Rectifiable fields — only CONFIDENTIAL/RESTRICTED fields may appear here. */
    private static final Set<String> RECTIFIABLE_FIELDS = Set.of(
            "name", "legalName", "primaryContactName",
            "primaryContactEmail", "primaryContactPhone",
            "contactEmail", "contactPhone", "billingAddress"
    );

    private final CustomerRepository customerRepository;
    private final EntityManager entityManager;

    public IdentitySubjectDataRectifier(CustomerRepository customerRepository,
                                         EntityManager entityManager) {
        this.customerRepository = customerRepository;
        this.entityManager      = entityManager;
    }

    @Override
    public String module() { return "identity"; }

    @Override
    public List<String> supportedSubjectTypes() { return List.of("CUSTOMER"); }

    @Override
    public RectifyFieldResult rectify(SubjectRef ref, String entityName, String fieldName, String newValue) {
        if (!"Customer".equals(entityName)) {
            return RectifyFieldResult.skipped(entityName, fieldName, "not owned by identity module");
        }
        if (!RECTIFIABLE_FIELDS.contains(fieldName)) {
            return RectifyFieldResult.refused(entityName, fieldName,
                    "field '" + fieldName + "' is not on the Customer rectification allow-list");
        }

        Optional<Customer> opt = customerRepository.findById(ref.subjectId());
        if (opt.isEmpty()) {
            return RectifyFieldResult.refused(entityName, fieldName,
                    "Customer not found for subjectId=" + ref.subjectId());
        }
        Customer customer = opt.get();

        try {
            applyField(customer, fieldName, newValue);
            customerRepository.save(customer);
            // Force flush so Envers writes the revision; revision number is obtained via AuditReader
            entityManager.flush();
            long revisionId = resolveLatestRevision(ref.subjectId());
            return RectifyFieldResult.applied(entityName, fieldName, revisionId);
        } catch (IllegalArgumentException ex) {
            return RectifyFieldResult.refused(entityName, fieldName, ex.getMessage());
        }
    }

    private void applyField(Customer customer, String fieldName, String newValue) {
        switch (fieldName) {
            case "name"                -> customer.setName(newValue);
            case "legalName"           -> customer.setLegalName(newValue);
            case "primaryContactName"  -> customer.setPrimaryContactName(newValue);
            case "primaryContactEmail" -> customer.setPrimaryContactEmail(newValue);
            case "primaryContactPhone" -> customer.setPrimaryContactPhone(newValue);
            case "contactEmail"        -> customer.setContactEmail(newValue);
            case "contactPhone"        -> customer.setContactPhone(newValue);
            case "billingAddress"      -> customer.setBillingAddress(newValue);
            default -> throw new IllegalArgumentException("Unknown field: " + fieldName);
        }
    }

    private long resolveLatestRevision(java.util.UUID customerId) {
        // Use JPQL to get the latest audit revision number for this customer
        try {
            Long rev = (Long) entityManager.createNativeQuery(
                            "SELECT MAX(rev) FROM customer_aud WHERE id = ?1")
                    .setParameter(1, customerId)
                    .getSingleResult();
            return rev != null ? rev : -1L;
        } catch (Exception ex) {
            return -1L;
        }
    }
}
