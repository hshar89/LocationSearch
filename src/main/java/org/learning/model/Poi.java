package org.learning.model;

import java.util.List;

public record Poi(
        String name,
        String displayName,
        List<String> aliases,
        String category,
        double confidence,
        double lat,
        double lon,
        String source,
        int sourceSignalCount,
        boolean hasWikiEntry,
        boolean hasWebPresence
) {
}
