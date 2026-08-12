package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.api.SubjectSection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SubjectDataProvider for the workorder module.
 *
 * <p>Collects work orders and assignments associated with the subject.
 * For TECHNICIAN subjects: work orders they were assigned to.
 * For CUSTOMER_CONTACT subjects: work orders at their customer's sites.
 */
@Component
class WorkOrderSubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    WorkOrderSubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "workorder.work_order";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("TECHNICIAN", "APP_USER", "CUSTOMER_CONTACT");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        List<Map<String, Object>> rows = new ArrayList<>();

        if ("TECHNICIAN".equals(ref.subjectType())) {
            rows = jdbc.queryForList(
                    "SELECT wo.id, wo.title, wo.state, wo.created_at, wo.closed_at " +
                    "FROM work_order wo " +
                    "JOIN assignment a ON a.work_order_id = wo.id " +
                    "WHERE a.technician_id = ?", ref.subjectId());
        } else if ("APP_USER".equals(ref.subjectType())) {
            // Work orders created by this user
            rows = jdbc.queryForList(
                    "SELECT id, title, state, created_at, closed_at " +
                    "FROM work_order WHERE created_by = ?::text", ref.subjectId());
        }

        return new SubjectSection(sectionName(), "workorder", 1, rows.size(), rows);
    }
}
