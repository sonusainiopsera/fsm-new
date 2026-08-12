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
 * SubjectDataProvider for the identity module.
 *
 * <p>Collects {@code app_user} records for the given subject.
 * Password hashes and token hashes are excluded (Restricted fields).
 */
@Component
class IdentitySubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    IdentitySubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "identity.app_user";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("APP_USER", "*");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email, display_name, created_at, updated_at " +
                "FROM app_user WHERE id = ?", ref.subjectId());
        return new SubjectSection(sectionName(), "identity", 1, rows.size(), rows);
    }
}
