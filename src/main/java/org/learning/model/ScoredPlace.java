package org.learning.model;

// A place id paired with its blended ranking score for a given prefix. Serialized into the
// trie-ranking snapshot the batch job publishes to S3 and the serving JVM hot-reloads.
public record ScoredPlace(
        String id,
        double score
) {
}
