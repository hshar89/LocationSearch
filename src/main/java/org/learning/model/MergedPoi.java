package org.learning.model;

import java.util.List;

public record MergedPoi(
        String id,
        String display_name,
        List<String> normalizedAliases,
        List<String> categories,
        double confidence,
        double lat,
        double lng,
        List<String> sources,
        double seedScore
) {
}
