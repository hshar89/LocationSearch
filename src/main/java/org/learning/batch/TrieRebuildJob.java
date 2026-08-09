package org.learning.batch;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.learning.model.PlaceMetrics;
import org.learning.model.PrefixPlaceCombinationMetrics;
import org.learning.service.S3EventService;
import org.learning.setup.TrieBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TrieRebuildJob {

    // Runs 30 minutes after RankMetricCollectorJob (01:00 UTC) to ensure metrics are written first.
    private static final String CRON_DAILY_130AM_UTC = "0 30 1 * * *";
    private static final String LOCK_AT_MOST = "PT30M";
    private static final String LOCK_AT_LEAST = "PT10M";

    // How many days of metric delta files to aggregate. Older days contribute decaying signals;
    // 30 days is a reasonable window without over-weighting stale behavior.
    private static final int METRIC_WINDOW_DAYS = 30;

    private final S3EventService metricsS3Service;

    public TrieRebuildJob(S3EventService metricsS3Service) {
        this.metricsS3Service = metricsS3Service;
    }

    @Scheduled(cron = CRON_DAILY_130AM_UTC, zone = "UTC")
    @SchedulerLock(name = "trie-rebuild-job", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = LOCK_AT_LEAST)
    public void run() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("TrieRebuildJob starting, aggregating {} days ending {}", METRIC_WINDOW_DAYS, yesterday);

        // --- Step 1: Aggregate metric delta files for the last N days ---
        // Each day's batch job writes:
        //   prefix-place/<date>/batch.ndjson
        //   place/<date>/batch.ndjson
        // We read all of them and deserialize into running lists that the TrieBuilder will sum.
        List<String> comboRaw = new ArrayList<>();
        List<String> placeRaw = new ArrayList<>();

        for (int i = 0; i < METRIC_WINDOW_DAYS; i++) {
            LocalDate date = yesterday.minusDays(i);
            comboRaw.addAll(metricsS3Service.readAllUnderPrefix("prefix-place/" + date + "/"));
            placeRaw.addAll(metricsS3Service.readAllUnderPrefix("place/" + date + "/"));
        }

        List<PrefixPlaceCombinationMetrics> comboMetrics =
                metricsS3Service.deserialize(comboRaw, PrefixPlaceCombinationMetrics.class);
        List<PlaceMetrics> placeMetrics =
                metricsS3Service.deserialize(placeRaw, PlaceMetrics.class);

        log.info("Loaded {} combo metrics and {} place metrics across {} days",
                comboMetrics.size(), placeMetrics.size(), METRIC_WINDOW_DAYS);

        // --- Step 2: Rebuild trie with blended scores ---
        try {
            new TrieBuilder().buildWithMetrics(comboMetrics, placeMetrics);
            log.info("TrieRebuildJob completed for day: {}", yesterday);
        } catch (Exception e) {
            log.error("TrieRebuildJob failed: {}", e.getMessage(), e);
        }
    }
}
