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
 * SubjectDataProvider for the analytics module.
 *
 * <p>Collects GPS location traces from {@code technician_position} for TECHNICIAN subjects.
 * Positions are personal data under GDPR Article 4 and BR-26.
 */
@Component
class AnalyticsSubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    AnalyticsSubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "analytics.location_traces";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("TECHNICIAN");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, latitude, longitude, captured_at " +
                "FROM technician_position WHERE technician_id = ? ORDER BY captured_at DESC",
                ref.subjectId());
        return new SubjectSection(sectionName(), "analytics", 1, rows.size(), rows);
    }
}
