package com.fieldservice.inventory.privacy;

import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectDataSection;
import com.fieldservice.privacy.api.SubjectRef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provides inventory-module personal data for DSAR exports.
 *
 * <p>Returns parts-consumption and stock-movement records for {@code TECHNICIAN} subjects.
 */
@Component
public class InventorySubjectDataProvider implements SubjectDataProvider {

    @Override
    public String sectionName() {
        return "inventory_movements";
    }

    @Override
    public String sourceModule() {
        return "inventory";
    }

    @Override
    public List<String> supportedSubjectTypes() {
        return List.of("TECHNICIAN");
    }

    @Override
    public SubjectDataSection collect(SubjectRef ref) {
        // Placeholder implementation — full data collection wired in a follow-up story.
        return SubjectDataSection.empty(sectionName(), sourceModule());
    }
}
