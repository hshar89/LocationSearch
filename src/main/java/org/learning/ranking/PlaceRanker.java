package org.learning.ranking;

public class PlaceRanker {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    // Exponential decay scale length: closeness roughly halves every ~1386m
    // (ln(2) * 2000). Tune this to make far-away places drop off faster/slower.
    private static final double DECAY_SCALE_METERS = 2000.0;

    // alpha: popularity vs proximity blend weight. Popularity-leaning so
    // well-known places still surface for users slightly outside their radius.
    private static final double POPULARITY_WEIGHT = 0.65;

    // seedScore is an unbounded additive score (confidence*sourceCount + signal
    // weights + destination bonus); observed max in the NYC dataset is ~7.5.
    // Capped here with headroom so popularity saturates at 1.0 rather than
    // requiring an exact theoretical maximum.
    private static final double SEED_SCORE_CAP = 10.0;

    public static double score(double userLat, double userLng, double placeLat, double placeLng, double seedScore) {
        double distance = haversineMeters(userLat, userLng, placeLat, placeLng);
        double closeness = Math.exp(-distance / DECAY_SCALE_METERS);
        double popularity = Math.min(seedScore / SEED_SCORE_CAP, 1.0);
        return POPULARITY_WEIGHT * popularity + (1 - POPULARITY_WEIGHT) * closeness;
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
