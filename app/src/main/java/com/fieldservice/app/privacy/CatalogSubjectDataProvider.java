package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.api.SubjectSection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SubjectDataProvider for the catalog module (customers and sites).
 *
 * <p>Collects customer account records for CUSTOMER_CONTACT subjects.
 */
@Component
class CatalogSubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    CatalogSubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "catalog.customer";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("CUSTOMER_CONTACT", "APP_USER");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        // Customer contact personal data: name, email, phone on customer records
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, contact_email, primary_contact_name, " +
                "primary_contact_email, primary_contact_phone, created_at " +
                "FROM customer WHERE id = ?", ref.subjectId());
        return new SubjectSection(sectionName(), "catalog", 1, rows.size(), rows);
    }
}
