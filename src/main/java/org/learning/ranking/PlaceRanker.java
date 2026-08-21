package org.learning.ranking;

public class PlaceRanker {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    // Exponential decay scale length: closeness roughly halves every ~1386m
    // (ln(2) * 2000). Tune this to make far-away places drop off faster/slower.
    private static final double DECAY_SCALE_METERS = 2000.0;

    // Behavioral score vs proximity blend weight. Behavior-leaning: the snapshot's blended score
    // (popularity + selection rate + MRR - effort - abandonment) is the learned signal we want to
    // dominate, with proximity as a location tie-breaker.
    private static final double BEHAVIOR_WEIGHT = 0.70;

    /**
     * Final serving score for a candidate: blends its prefix-specific behavioral score (from the
     * published ranking snapshot) with proximity to the user. When the caller supplies no location
     * (lat/lng of 0), closeness collapses to ~0 for real POIs and ranking is behavior-only.
     */
    public static double blend(double behavioralScore, double userLat, double userLng,
                               double placeLat, double placeLng) {
        double distance = haversineMeters(userLat, userLng, placeLat, placeLng);
        double closeness = Math.exp(-distance / DECAY_SCALE_METERS);
        return BEHAVIOR_WEIGHT * behavioralScore + (1 - BEHAVIOR_WEIGHT) * closeness;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);
        double h = sinHalfLat * sinHalfLat
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinHalfLon * sinHalfLon;
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

        return EARTH_RADIUS_METERS * c;
    }
}
