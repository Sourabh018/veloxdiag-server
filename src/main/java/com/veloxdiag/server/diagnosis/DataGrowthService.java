package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers "why did this slowdown start NOW" by comparing the earliest and
 * most recent row-count estimate captured for each table an endpoint's seq
 * scans touch. A table that was 500 rows when first captured and is 8,000
 * rows in the most recent capture didn't get a worse query written against
 * it — it just grew underneath an already-borderline query, and a scan that
 * was fine six months ago is a real problem today. DiagnosisService answers
 * WHY (N+1, missing index); this answers WHEN/WHY NOW.
 *
 * Reuses the SAME regex DiagnosisService uses for MISSING_INDEX_CANDIDATE,
 * kept as an identical copy rather than a shared import — the two call
 * sites parse the same EXPLAIN text shape for different purposes (current
 * severity vs. trend over time), and coupling them would make an unrelated
 * change to one accidentally affect the other.
 */
@Service
public class DataGrowthService {

    private static final Pattern SEQ_SCAN_PATTERN = Pattern.compile(
            "Seq Scan on (\\w+)(?:\\s+\\w+)?\\s*\\(cost=[\\d.]+\\.\\.[\\d.]+ rows=(\\d+)"
    );

    // A trend needs at least this many distinct captures spread over time to
    // be worth showing — 2 captures could just be normal planner estimate
    // noise between two close-together requests, not a real growth signal.
    private static final int MIN_DATA_POINTS = 3;

    // Below this growth, don't bother surfacing it — normal fluctuation in
    // the planner's row estimate, not a meaningful "this table grew" story.
    private static final double MIN_GROWTH_PERCENT_TO_REPORT = 15.0;

    private final SlowQueryPlanRepository slowQueryPlanRepository;

    public DataGrowthService(SlowQueryPlanRepository slowQueryPlanRepository) {
        this.slowQueryPlanRepository = slowQueryPlanRepository;
    }

    public List<TableGrowthTrend> getGrowthTrends(String endpoint) {
        List<SlowQueryPlan> plans = slowQueryPlanRepository
                .findByEndpointAndContainsSeqScanTrueOrderByTimestampAsc(endpoint);

        // table name -> ordered list of (timestamp, rowCount) seen for it,
        // oldest first (plans list is already ordered ascending by timestamp)
        Map<String, List<long[]>> rowCountsByTable = new LinkedHashMap<>();
        Map<String, List<java.time.LocalDateTime>> timestampsByTable = new LinkedHashMap<>();

        for (SlowQueryPlan plan : plans) {
            Matcher matcher = SEQ_SCAN_PATTERN.matcher(plan.getExplainPlan());
            while (matcher.find()) {
                String table = matcher.group(1);
                long rows = Long.parseLong(matcher.group(2));
                rowCountsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(new long[]{rows});
                timestampsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(plan.getTimestamp());
            }
        }

        List<TableGrowthTrend> trends = new ArrayList<>();
        for (Map.Entry<String, List<long[]>> entry : rowCountsByTable.entrySet()) {
            String table = entry.getKey();
            List<long[]> rowCounts = entry.getValue();
            List<java.time.LocalDateTime> timestamps = timestampsByTable.get(table);

            if (rowCounts.size() < MIN_DATA_POINTS) continue;

            long earliest = rowCounts.get(0)[0];
            long latest = rowCounts.get(rowCounts.size() - 1)[0];
            java.time.LocalDateTime earliestAt = timestamps.get(0);
            java.time.LocalDateTime latestAt = timestamps.get(timestamps.size() - 1);

            TableGrowthTrend trend = new TableGrowthTrend(table, earliest, earliestAt, latest, latestAt, rowCounts.size());
            if (trend.getGrowthPercent() >= MIN_GROWTH_PERCENT_TO_REPORT) {
                trends.add(trend);
            }
        }

        return trends;
    }
}