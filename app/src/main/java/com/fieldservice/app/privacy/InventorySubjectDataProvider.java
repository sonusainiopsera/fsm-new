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
 * SubjectDataProvider for the inventory module.
 *
 * <p>Collects stock ledger movements attributed to the subject (technician stock usage).
 */
@Component
class InventorySubjectDataProvider implements SubjectDataProvider {

    private final JdbcTemplate jdbc;

    InventorySubjectDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sectionName() {
        return "inventory.stock_ledger";
    }

    @Override
    public Set<String> supportedSubjectTypes() {
        return Set.of("TECHNICIAN", "APP_USER");
    }

    @Override
    public SubjectSection collect(SubjectRef ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sl.id, sl.movement_type, sl.quantity_delta, sl.created_at, sl.created_by " +
                "FROM stock_ledger sl WHERE sl.created_by = ?::text", ref.subjectId());
        return new SubjectSection(sectionName(), "inventory", 1, rows.size(), rows);
    }
}
