package org.learning.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.PrefixRanking;
import org.learning.model.ScoredPlace;
import org.learning.model.Trie;
import org.learning.service.S3EventService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a built {@link Trie} into the flat, prefix-keyed ranking snapshot used both as the
 * serving index (in memory) and as the artifact published to S3.
 *
 * <p>The serving path only ever looks up the full requested prefix, so a {@code prefix -> ordered
 * places} map is functionally equivalent to walking the trie, while being trivial to serialize as
 * NDJSON (one {@link PrefixRanking} per line) and to shard for large datasets.
 */
public final class TrieSnapshot {

    // Max prefix lines per S3 object. Bounds the in-memory string size when publishing a large
    // snapshot; the loader reads every shard under the snapshot prefix and merges them.
    private static final int SHARD_LINES = 50_000;

    private TrieSnapshot() {
    }

    private interface NodeVisitor {
        void visit(String prefix, List<String> placeIds, List<Double> scores) throws IOException;
    }

    /** DFS over the trie, emitting each prefix node that carries places (root is skipped). */
    private static void walk(Trie root, NodeVisitor visitor) throws IOException {
        walk(root, new StringBuilder(), visitor);
    }

    private static void walk(Trie node, StringBuilder path, NodeVisitor visitor) throws IOException {
        for (Map.Entry<Character, Trie> entry : node.getChildren().entrySet()) {
            Trie child = entry.getValue();
            path.append(entry.getKey());
            if (!child.getPlaceIds().isEmpty()) {
                visitor.visit(path.toString(), child.getPlaceIds(), child.getScores());
            }
            walk(child, path, visitor);
            path.deleteCharAt(path.length() - 1);
        }
    }

    private static List<ScoredPlace> zip(List<String> placeIds, List<Double> scores) {
        List<ScoredPlace> places = new ArrayList<>(placeIds.size());
        for (int i = 0; i < placeIds.size(); i++) {
            double score = (scores != null && i < scores.size()) ? scores.get(i) : 0.0;
            places.add(new ScoredPlace(placeIds.get(i), score));
        }
        return places;
    }

    /** Builds the in-memory serving index (prefix -> places, highest score first). */
    public static Map<String, List<ScoredPlace>> toIndex(Trie root) {
        Map<String, List<ScoredPlace>> index = new HashMap<>();
        try {
            walk(root, (prefix, placeIds, scores) -> index.put(prefix, zip(placeIds, scores)));
        } catch (IOException e) {
            // The in-memory visitor never performs IO; rethrow defensively.
            throw new RuntimeException(e);
        }
        return index;
    }

    /**
     * Serializes the trie to S3 as sharded NDJSON under {@code basePrefix} (e.g.
     * {@code trie-snapshot/2026-08-20/}), one {@link PrefixRanking} per line. The caller writes the
     * {@code latest} pointer only after this returns, so readers never observe a partial snapshot.
     */
    public static void writeSharded(Trie root, S3EventService store, String basePrefix) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        ShardWriter writer = new ShardWriter(store, basePrefix, objectMapper);
        walk(root, writer::accept);
        writer.flushRemaining();
    }

    private static final class ShardWriter {
        private final S3EventService store;
        private final String basePrefix;
        private final ObjectMapper objectMapper;
        private final StringBuilder buffer = new StringBuilder();
        private int linesInShard = 0;
        private int shardIndex = 0;

        ShardWriter(S3EventService store, String basePrefix, ObjectMapper objectMapper) {
            this.store = store;
            this.basePrefix = basePrefix;
            this.objectMapper = objectMapper;
        }

        void accept(String prefix, List<String> placeIds, List<Double> scores) throws IOException {
            buffer.append(objectMapper.writeValueAsString(new PrefixRanking(prefix, zip(placeIds, scores))))
                    .append('\n');
            if (++linesInShard >= SHARD_LINES) {
                flush();
            }
        }

        void flushRemaining() {
            if (buffer.length() > 0) {
                flush();
            }
        }

        private void flush() {
            store.writeKey(basePrefix + "part-" + shardIndex + ".ndjson", buffer.toString());
            buffer.setLength(0);
            linesInShard = 0;
            shardIndex++;
        }
    }

    /** Merges the NDJSON shard contents read from S3 into a serving index. */
    public static Map<String, List<ScoredPlace>> loadIndex(List<String> shardContents, ObjectMapper objectMapper) {
        Map<String, List<ScoredPlace>> index = new HashMap<>();
        for (String content : shardContents) {
            for (String line : content.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    PrefixRanking ranking = objectMapper.readValue(line, PrefixRanking.class);
                    index.put(ranking.prefix(), ranking.places());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse snapshot line: " + e.getMessage(), e);
                }
            }
        }
        return index;
    }
}
