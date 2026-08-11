package com.fieldservice.workorder.privacy;

import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rectifier for work-order module personal data.
 *
 * <p>Work orders themselves hold no directly rectifiable PII fields (fault descriptions
 * are operational content, not personal data fields in the classification registry).
 * Customer identity corrections route to {@link com.fieldservice.identity.privacy.IdentitySubjectDataRectifier}.
 *
 * <p>This stub returns {@link RectifyFieldResult#skipped} for all entities, satisfying the
 * module boundary contract. Extend with actual entity corrections when the classification
 * registry identifies rectifiable fields in this module.
 */
@Component
public class WorkOrderSubjectDataRectifier implements SubjectDataRectifier {

    @Override
    public String module() { return "workorder"; }

    @Override
    public List<String> supportedSubjectTypes() {
        return List.of("CUSTOMER", "TECHNICIAN");
    }

    @Override
    public RectifyFieldResult rectify(SubjectRef ref, String entityName, String fieldName, String newValue) {
        // No rectifiable PII fields in the workorder module entities.
        return RectifyFieldResult.skipped(entityName, fieldName,
                "no rectifiable fields in workorder module for entity=" + entityName);
    }
}
