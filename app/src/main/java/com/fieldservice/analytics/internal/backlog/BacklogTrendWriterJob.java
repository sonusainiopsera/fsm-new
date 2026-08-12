package com.fieldservice.analytics.internal.backlog;

import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily job that writes immutable point-in-time backlog snapshots to {@code kpi_trend_point}.
 *
 * <p>Immutability is enforced by {@code INSERT ... ON CONFLICT DO NOTHING}: once a trend
 * point for a given (metric_key, segment_key, bucket_date) is written, a later work order
 * state change cannot alter that historical record.  Operations managers therefore see a
 * stable view of last month that does not retroactively change as jobs close.
 *
 * <p>Runs on the worker profile under a distributed lock to prevent duplicate writes
 * when multiple worker replicas are active.
 *
 * <p>Writes are against the primary data source (not the replica) to ensure it reads
 * the most current work-order state at the time the daily snapshot is taken.
 */
@Component
@Profile("worker")
public class BacklogTrendWriterJob {

    private static final Logger log = LoggerFactory.getLogger(BacklogTrendWriterJob.class);

    private static final String LOCK_NAME  = "analytics-backlog-trend-writer";
    private static final int    LEASE_SECS = 120;

    private static final String INSERT_SQL =
            "INSERT INTO kpi_trend_point (metric_key, segment_key, bucket_date, value, sample_count, written_at) " +
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate        primaryJdbc;
    private final JdbcSchedulingLock  schedulingLock;
    private final Clock               clock;

    public BacklogTrendWriterJob(
            JdbcTemplate              primaryJdbc,
            JdbcSchedulingLock        schedulingLock,
            Clock                     clock) {
        this.primaryJdbc    = primaryJdbc;
        this.schedulingLock = schedulingLock;
        this.clock          = clock;
    }

    @Scheduled(cron = "${app.analytics.backlog-trend-cron:0 5 0 * * *}")
    public void writeTrend() {
        schedulingLock.runIfLeader(LOCK_NAME, holderName(), LEASE_SECS, this::doWrite);
    }

    @Transactional
    public void doWrite() {
        LocalDate today    = LocalDate.now(clock);
        Date      bucket   = Date.valueOf(today);
        Instant   writtenAt = clock.instant();

        String stateIn = WorkOrderStatus.openStates().stream()
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(","));

        // Count open WOs per state for today's snapshot
        List<Map<String, Object>> stateRows = primaryJdbc.queryForList(
                "SELECT state, COUNT(*) AS cnt FROM work_order " +
                "WHERE state IN (" + stateIn + ") GROUP BY state");

        long total = 0L;
        for (Map<String, Object> row : stateRows) {
            String state = (String) row.get("state");
            long   cnt   = toLong(row.get("cnt"));
            total += cnt;
            insertPoint(BacklogCalculator.METRIC_KEY, "STATE:" + state, bucket, cnt, (int) cnt, writtenAt);
        }

        // ALL total
        insertPoint(BacklogCalculator.METRIC_KEY, "ALL", bucket, total, (int) total, writtenAt);

        log.info("backlog_trend_written bucket={} total={}", today, total);
    }

    private void insertPoint(String metricKey, String segmentKey, Date bucketDate,
                              long value, int sampleCount, Instant writtenAt) {
        primaryJdbc.update(INSERT_SQL,
                metricKey, segmentKey, bucketDate,
                BigDecimal.valueOf(value), sampleCount,
                Timestamp.from(writtenAt));
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static String holderName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
