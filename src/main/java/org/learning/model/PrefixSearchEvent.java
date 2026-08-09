package org.learning.model;

import java.util.List;

public record PrefixSearchEvent(
        String sessionId,
        String prefix,
        List<String> resultIds,
        double userLat,
        double userLng,
        long timestampMs
) {}
