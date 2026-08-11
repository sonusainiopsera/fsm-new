package com.fieldservice.identity.privacy;

import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectDataSection;
import com.fieldservice.privacy.api.SubjectRef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provides identity-module personal data (app_user, role_assignment) for DSAR exports.
 *
 * <p>Returns personal data for {@code APP_USER} subjects. Envelope-encrypted fields
 * are decrypted internally so the privacy module never handles key material.
 */
@Component
public class IdentitySubjectDataProvider implements SubjectDataProvider {

    @Override
    public String sectionName() {
        return "identity";
    }

    @Override
    public String sourceModule() {
        return "identity";
    }

    @Override
    public List<String> supportedSubjectTypes() {
        return List.of("APP_USER", "CUSTOMER");
    }

    @Override
    public SubjectDataSection collect(SubjectRef ref) {
        // Placeholder implementation — full data collection wired in a follow-up story.
        return SubjectDataSection.empty(sectionName(), sourceModule());
    }
}
