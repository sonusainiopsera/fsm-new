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
 * SubjectDataProvider for the portal module.
 *
 * <p>Collects portal account user records for the given subject.
 */
@Component
class PortalSubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    PortalSubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "portal.portal_account_user";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("APP_USER", "PORTAL_USER");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pau.id, pau.email, pau.status, pau.invited_at, pau.accepted_at " +
                "FROM portal_account_user pau " +
                "WHERE pau.app_user_id = ?", ref.subjectId());
        return new SubjectSection(sectionName(), "portal", 1, rows.size(), rows);
    }
}
