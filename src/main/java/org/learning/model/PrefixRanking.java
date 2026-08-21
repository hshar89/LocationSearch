package org.learning.model;

import java.util.List;

// One line of the published trie-ranking snapshot: a normalized prefix and its top places in
// descending blended-score order. The serving index is keyed by {@code prefix}.
public record PrefixRanking(
        String prefix,
        List<ScoredPlace> places
) {
}
