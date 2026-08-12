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
 * SubjectDataProvider for the workforce (technician) module.
 *
 * <p>Collects technician personal data for APP_USER subjects (join via user_id).
 */
@Component
class WorkforceSubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    WorkforceSubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "workforce.technician";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("APP_USER", "TECHNICIAN");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        String query = "TECHNICIAN".equals(ref.subjectType())
                ? "SELECT id, full_name, mobile_phone, employee_code, display_name, " +
                  "timezone, active, created_at FROM technician WHERE id = ?"
                : "SELECT id, full_name, mobile_phone, employee_code, display_name, " +
                  "timezone, active, created_at FROM technician WHERE user_id = ?";

        List<Map<String, Object>> rows = jdbc.queryForList(query, ref.subjectId());
        return new SubjectSection(sectionName(), "workforce", 1, rows.size(), rows);
    }
}
