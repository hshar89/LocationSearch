package org.learning.ranking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.ScoredPlace;
import org.learning.service.S3EventService;
import org.learning.setup.TrieSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the prefix-ranking index the serving path reads, and hot-swaps it when the batch job
 * publishes a newer snapshot to S3. This is what closes the learning loop: the behaviorally-ranked
 * trie built by {@code TrieRebuildJob} finally reaches the serving JVM.
 *
 * <p>The provider is resilient to S3/MinIO being unavailable: it starts serving the in-memory
 * bootstrap index immediately and keeps retrying the connection + reload on its poll interval, so
 * the serving process never hard-depends on object storage being up.
 */
public class RankingIndexProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RankingIndexProvider.class);

    // Pointer object whose body is the key prefix of the current snapshot (written last by the
    // rebuild job, so readers never see a half-written snapshot).
    private static final String LATEST_POINTER_KEY = "trie-snapshot/latest";
    private static final long POLL_INTERVAL_SECONDS = 300;

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<Map<String, List<ScoredPlace>>> indexRef;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ranking-index-reloader");
                t.setDaemon(true);
                return t;
            });

    // Built lazily on the poll thread so a down MinIO at startup doesn't crash the server.
    private volatile S3EventService snapshotStore;
    private volatile String loadedVersion;

    public RankingIndexProvider(String endpoint, String accessKey, String secretKey, String bucket,
                                Map<String, List<ScoredPlace>> bootstrapIndex) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.indexRef = new AtomicReference<>(bootstrapIndex);
    }

    /** The current serving index. Reads are lock-free; the reference is swapped atomically. */
    public Map<String, List<ScoredPlace>> current() {
        return indexRef.get();
    }

    /** Loads any existing snapshot once, then schedules periodic reloads. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::reloadQuietly, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void reloadQuietly() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("Snapshot reload attempt failed (will retry): {}", e.getMessage());
        }
    }

    private void reload() {
        if (snapshotStore == null) {
            // May throw if MinIO is unreachable; caught by reloadQuietly and retried next tick.
            snapshotStore = new S3EventService(endpoint, accessKey, secretKey, bucket);
        }
        String version = snapshotStore.readKeyOrNull(LATEST_POINTER_KEY);
        if (version == null || version.isBlank()) {
            return; // no snapshot published yet — keep serving the bootstrap/last-loaded index
        }
        version = version.trim();
        if (version.equals(loadedVersion)) {
            return; // already serving this snapshot
        }

        List<String> shards = snapshotStore.readAllUnderPrefix(version);
        if (shards.isEmpty()) {
            log.warn("Snapshot pointer {} references empty prefix {}; keeping current index",
                    LATEST_POINTER_KEY, version);
            return;
        }
        Map<String, List<ScoredPlace>> newIndex = TrieSnapshot.loadIndex(shards, objectMapper);
        indexRef.set(newIndex);
        loadedVersion = version;
        log.info("Loaded behavioral ranking snapshot {} ({} prefixes)", version, newIndex.size());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
