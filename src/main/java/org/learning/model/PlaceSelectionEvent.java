package org.learning.model;

public record PlaceSelectionEvent(
        String sessionId,
        String placeId,
        long timestampMs
) {}
