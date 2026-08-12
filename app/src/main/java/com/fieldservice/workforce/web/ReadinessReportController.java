package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.ReadinessReportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports/certification-readiness")
public class ReadinessReportController {

    private static final int DEFAULT_WARNING_WINDOW = 14;

    private final ReadinessReportService reportService;

    public ReadinessReportController(ReadinessReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ReadinessAggregateResponse getReadiness(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate,
            @RequestParam(defaultValue = "14") int warningWindowDays) {
        LocalDate date = atDate != null ? atDate : LocalDate.now();
        return reportService.computeReadiness(date, warningWindowDays);
    }

    @GetMapping("/gaps")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public PagedResponse<TechnicianGapResponse> getGaps(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate,
            @RequestParam(defaultValue = "14") int warningWindowDays,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate date = atDate != null ? atDate : LocalDate.now();
        return reportService.getGaps(date, warningWindowDays, page, size);
    }

    @GetMapping(value = "/gaps.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void downloadGapsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate,
            @RequestParam(defaultValue = "14") int warningWindowDays,
            HttpServletResponse response) throws IOException {
        LocalDate date = atDate != null ? atDate : LocalDate.now();
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"certification-gaps-" + date + ".csv\"");
        List<TechnicianGapResponse> gaps = reportService.getAllGapsForCsv(date, warningWindowDays);
        PrintWriter writer = response.getWriter();
        writer.println("employeeCode,displayName,missingFields,missingCertificationTypes,expiredCertificationTypes,expiringSoonCertificationTypes");
        for (TechnicianGapResponse gap : gaps) {
            writer.printf("%s,%s,%s,%s,%s,%s%n",
                    csvEscape(gap.employeeCode()),
                    csvEscape(gap.displayName()),
                    csvEscape(String.join("|", gap.missingFields())),
                    csvEscape(String.join("|", gap.missingCertificationTypes())),
                    csvEscape(String.join("|", gap.expiredCertificationTypes())),
                    csvEscape(String.join("|", gap.expiringSoonCertificationTypes())));
        }
        writer.flush();
    }

    @GetMapping("/snapshots")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public PagedResponse<ReadinessSnapshotResponse> listSnapshots(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return reportService.listSnapshots(page, size);
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ReadinessSnapshotResponse triggerSnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate,
            @RequestParam(defaultValue = "14") int warningWindowDays) {
        LocalDate date = atDate != null ? atDate : LocalDate.now();
        String isoWeek = com.fieldservice.workforce.internal.ReadinessSnapshotScheduler.isoWeekKey(date);
        return reportService.generateSnapshot(date, warningWindowDays, isoWeek);
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
