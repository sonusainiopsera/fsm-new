package com.fieldservice.workorder.privacy;

import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectDataSection;
import com.fieldservice.privacy.api.SubjectRef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provides work-order-module personal data for DSAR exports.
 *
 * <p>Returns work order history for {@code CUSTOMER} and {@code TECHNICIAN} subjects.
 */
@Component
public class WorkOrderSubjectDataProvider implements SubjectDataProvider {

    @Override
    public String sectionName() {
        return "work_orders";
    }

    @Override
    public String sourceModule() {
        return "workorder";
    }

    @Override
    public List<String> supportedSubjectTypes() {
        return List.of("CUSTOMER", "TECHNICIAN");
    }

    @Override
    public SubjectDataSection collect(SubjectRef ref) {
        // Placeholder implementation — full data collection wired in a follow-up story.
        return SubjectDataSection.empty(sectionName(), sourceModule());
    }
}
