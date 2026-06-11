package com.ridedispatch;

import java.util.Comparator;

/**
 * DriverScoreComparator — the heart of the dispatch engine.
 *
 * MAX-HEAP: higher composite score = polled first = matched to the rider.
 *
 * Score formula (weights are tunable via config):
 *   distanceScore  (0–100) = (1 - dist/maxRadius) * 100   [closer = higher]
 *   ratingScore    (0–100) = (rating - 1.0) / 4.0 * 100   [5.0 → 100]
 *   acceptScore    (0–100) = acceptanceRate * 100           [1.0 → 100]
 *   surgeScore     (0–100) = (surge - 1.0) / 2.0 * 100    [3.0 surge → 100]
 *
 *   composite = distScore*0.40 + ratingScore*0.30 + acceptScore*0.20 + surgeScore*0.10
 *
 * Tie-breaking (5 levels — no two drivers ever produce same rank):
 *   1. compositeScore desc
 *   2. rating desc
 *   3. distanceKm asc
 *   4. acceptanceRate desc
 *   5. driverId asc (stable, deterministic)
 */
public class DriverScoreComparator implements Comparator<DriverCandidate> {

    // Weights — must sum to 1.0
    public static final double W_DISTANCE   = 0.40;
    public static final double W_RATING     = 0.30;
    public static final double W_ACCEPTANCE = 0.20;
    public static final double W_SURGE      = 0.10;

    public static final double MAX_RADIUS_KM = 5.0;

    /** Compute composite score (0–100). Higher = better. */
    public static double computeScore(DriverCandidate c) {
        double clampedDist = Math.min(c.distanceKm, MAX_RADIUS_KM);
        double distScore   = (1.0 - clampedDist / MAX_RADIUS_KM) * 100.0;
        double ratingScore = ((c.rating - 1.0) / 4.0) * 100.0;
        double acceptScore = c.acceptanceRate * 100.0;
        double surgeScore  = Math.min(((c.surgeMultiplier - 1.0) / 2.0) * 100.0, 100.0);

        return distScore   * W_DISTANCE
             + ratingScore * W_RATING
             + acceptScore * W_ACCEPTANCE
             + surgeScore  * W_SURGE;
    }

    @Override
    public int compare(DriverCandidate a, DriverCandidate b) {
        // 1. composite score — descending (higher is better → b vs a)
        int cmp = Double.compare(b.compositeScore, a.compositeScore);
        if (cmp != 0) return cmp;

        // 2. raw rating — descending
        cmp = Double.compare(b.rating, a.rating);
        if (cmp != 0) return cmp;

        // 3. distance — ascending (closer is better → a vs b)
        cmp = Double.compare(a.distanceKm, b.distanceKm);
        if (cmp != 0) return cmp;

        // 4. acceptance rate — descending
        cmp = Double.compare(b.acceptanceRate, a.acceptanceRate);
        if (cmp != 0) return cmp;

        // 5. stable tie-break — lower id wins (deterministic, no flapping)
        return Long.compare(a.driverId, b.driverId);
    }
}
