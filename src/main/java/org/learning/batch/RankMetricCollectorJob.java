package org.learning.batch;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.learning.model.PlaceMetrics;
import org.learning.model.PlaceSelectionEvent;
import org.learning.model.PrefixPlaceCombinationMetrics;
import org.learning.model.PrefixSearchEvent;
import org.learning.service.S3EventService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class RankMetricCollectorJob {

    private static final String CRON_DAILY_1AM_UTC = "0 0 1 * * *";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String LOCK_AT_MOST = "PT30M";
    private static final String LOCK_AT_LEAST = "PT15M";

    private final S3EventService s3EventService;
    private final S3EventService metricsS3Service;

    public RankMetricCollectorJob(S3EventService s3EventService, S3EventService metricsS3Service) {
        this.s3EventService = s3EventService;
        this.metricsS3Service = metricsS3Service;
    }

    @Scheduled(cron = CRON_DAILY_1AM_UTC, zone = "UTC")
    @SchedulerLock(name = "rank-metric-collector-job", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = LOCK_AT_LEAST)
    public void run() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate today = yesterday.plusDays(1);
        log.info("RankMetricCollectorJob starting for day: {}", yesterday);

        // --- Step 1: Read events from S3 ---
        List<String> prefixRaw = new ArrayList<>();
        prefixRaw.addAll(s3EventService.readEvents("prefix-search", yesterday, null));
        prefixRaw.addAll(s3EventService.readEvents("prefix-search", today, "00"));
        List<PrefixSearchEvent> prefixEvents = s3EventService.deserialize(prefixRaw, PrefixSearchEvent.class);

        List<String> selectionRaw = new ArrayList<>();
        selectionRaw.addAll(s3EventService.readEvents("place-selection", yesterday, null));
        selectionRaw.addAll(s3EventService.readEvents("place-selection", today, "00"));
        List<PlaceSelectionEvent> selectionEvents = s3EventService.deserialize(selectionRaw, PlaceSelectionEvent.class);

        // --- Step 2: Group all timestamps by sessionId ---
        Instant midnight = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, List<Long>> sessionTimestamps = new HashMap<>();
        for (PrefixSearchEvent e : prefixEvents) {
            sessionTimestamps.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e.timestampMs());
        }
        for (PlaceSelectionEvent e : selectionEvents) {
            sessionTimestamps.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e.timestampMs());
        }

        // --- Step 3: Keep only sessions that started yesterday ---
        Set<String> yesterdaySessions = new HashSet<>();
        for (Map.Entry<String, List<Long>> entry : sessionTimestamps.entrySet()) {
            long earliestTs = entry.getValue().stream().mapToLong(Long::longValue).min().getAsLong();
            if (Instant.ofEpochMilli(earliestTs).isBefore(midnight)) {
                yesterdaySessions.add(entry.getKey());
            }
        }

        // --- Step 4: Compute metrics ---

        // Index prefix events by sessionId, filtering to yesterday's sessions only.
        Map<String, List<PrefixSearchEvent>> sessionToPrefixEvents = new HashMap<>();
        for (PrefixSearchEvent e : prefixEvents) {
            if (yesterdaySessions.contains(e.sessionId())) {
                sessionToPrefixEvents.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e);
            }
        }

        // Index the selected placeId per session (one selection event per session assumed).
        Map<String, String> sessionToSelectedPlace = new HashMap<>();
        for (PlaceSelectionEvent sel : selectionEvents) {
            if (yesterdaySessions.contains(sel.sessionId())) {
                sessionToSelectedPlace.put(sel.sessionId(), sel.placeId());
            }
        }

        Map<String, PrefixPlaceCombinationMetrics> combos = new HashMap<>();
        Map<String, PlaceMetrics> placeMetricsMap = new HashMap<>();

        // Pass 1 — iterate prefix events, grouped by session.
        // For every (prefix, placeId) pair shown, accumulate impressions.
        // If this session ended in a selection and the place was the selected one,
        // also accumulate selection, rank, and RR — for every prefix where it appeared.
        // If the session had no selection, every impression counts as an abandonment.
        // PlaceMetrics first-appearance and first-top are tracked per-session.
        for (Map.Entry<String, List<PrefixSearchEvent>> entry : sessionToPrefixEvents.entrySet()) {
            String sessionId = entry.getKey();
            String selectedPlaceId = sessionToSelectedPlace.get(sessionId); // null if abandoned
            boolean abandoned = selectedPlaceId == null;

            List<PrefixSearchEvent> sortedEvents = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(PrefixSearchEvent::timestampMs))
                    .toList();

            // Per-session state for PlaceMetrics first-appearance / first-top tracking.
            Set<String> firstSeenThisSession = new HashSet<>();
            Set<String> firstTopThisSession = new HashSet<>();

            for (PrefixSearchEvent pe : sortedEvents) {
                List<String> results = pe.resultIds();
                for (int i = 0; i < results.size(); i++) {
                    String placeId = results.get(i);
                    int rank = i; // 0-based: 0 = top position
                    String comboKey = pe.prefix() + "|" + placeId;
                    final String pePrefix = pe.prefix();
                    final String pePlaceId = placeId;

                    PrefixPlaceCombinationMetrics combo = combos.computeIfAbsent(comboKey,
                            k -> new PrefixPlaceCombinationMetrics(pePrefix, pePlaceId, 0.0, 0L, 0L, 0L, 0L));
                    combo.setImpressions(combo.getImpressions() + 1);

                    if (abandoned) {
                        combo.setAbandonmentCounter(combo.getAbandonmentCounter() + 1);
                    } else if (placeId.equals(selectedPlaceId)) {
                        // Every prefix where the selected place appeared counts as a selection
                        // for that (prefix, place) combo.
                        combo.setSelections(combo.getSelections() + 1);
                        combo.setSumAtSelectionRank(combo.getSumAtSelectionRank() + rank);
                        combo.setSumReciprocalRank(combo.getSumReciprocalRank() + 1.0 / (rank + 1));

                        PlaceMetrics pm = placeMetricsMap.computeIfAbsent(placeId,
                                k -> new PlaceMetrics(placeId, 0L, 0L, 0L, 0L, 0L, 0L));
                        pm.setSumPrefixLenOnSelection(pm.getSumPrefixLenOnSelection() + pePrefix.length());
                        pm.setCountSelections(pm.getCountSelections() + 1);
                    }

                    // First appearance in this session — tracks how short a prefix surfaces the place.
                    if (firstSeenThisSession.add(placeId)) {
                        PlaceMetrics pm = placeMetricsMap.computeIfAbsent(placeId,
                                k -> new PlaceMetrics(placeId, 0L, 0L, 0L, 0L, 0L, 0L));
                        pm.setSumPrefixLenOnFirstAppearance(pm.getSumPrefixLenOnFirstAppearance() + pe.prefix().length());
                        pm.setCountFirstAppearances(pm.getCountFirstAppearances() + 1);
                    }

                    // First time at rank 0 in this session — tracks how short a prefix puts it on top.
                    if (rank == 0 && firstTopThisSession.add(placeId)) {
                        PlaceMetrics pm = placeMetricsMap.computeIfAbsent(placeId,
                                k -> new PlaceMetrics(placeId, 0L, 0L, 0L, 0L, 0L, 0L));
                        pm.setSumPrefixLenOnFirstTop(pm.getSumPrefixLenOnFirstTop() + pe.prefix().length());
                        pm.setCountFirstTop(pm.getCountFirstTop() + 1);
                    }
                }
            }
        }

        // --- Step 5: Write per-day delta files to place-ranking-metric bucket ---
        // Key pattern: prefix-place/<date>/batch.ndjson and place/<date>/batch.ndjson
        // Each file is NDJSON — one JSON object per line.
        // The downstream ranking job reads the last N days, sums the running totals,
        // and derives averages (sum / count) at query time.
        metricsS3Service.writeKey(
                "prefix-place/" + yesterday + "/batch.ndjson",
                toNdjson(combos.values())
        );
        metricsS3Service.writeKey(
                "place/" + yesterday + "/batch.ndjson",
                toNdjson(placeMetricsMap.values())
        );

        log.info("RankMetricCollectorJob completed for day: {} | combos={} | places={}",
                yesterday, combos.size(), placeMetricsMap.size());
    }

    private String toNdjson(Iterable<?> objects) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : objects) {
            try {
                sb.append(objectMapper.writeValueAsString(obj)).append("\n");
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize metric object: {}", e.getMessage());
            }
        }
        return sb.toString();
    }
}
