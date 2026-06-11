package com.ridedispatch;

/**
 * Object that lives inside the PriorityQueue during dispatch.
 * Combines: live location from Redis + profile from PostgreSQL + ride context.
 * Score is pre-computed once before adding to the PQ.
 */
public class DriverCandidate {
    // From PostgreSQL
    public final long   driverId;
    public final String driverName;
    public final String vehicleType;
    public final double rating;
    public final double acceptanceRate;
    public final int    totalTrips;

    // From Redis (live)
    public final double distanceKm;
    public final double driverLat;
    public final double driverLng;

    // From ride request context
    public final double surgeMultiplier;

    // Computed once before entering PQ
    public double compositeScore;

    public DriverCandidate(long driverId, String driverName, String vehicleType,
                           double rating, double acceptanceRate, int totalTrips,
                           double distanceKm, double driverLat, double driverLng,
                           double surgeMultiplier) {
        this.driverId        = driverId;
        this.driverName      = driverName;
        this.vehicleType     = vehicleType;
        this.rating          = rating;
        this.acceptanceRate  = acceptanceRate;
        this.totalTrips      = totalTrips;
        this.distanceKm      = distanceKm;
        this.driverLat       = driverLat;
        this.driverLng       = driverLng;
        this.surgeMultiplier = surgeMultiplier;
    }

    @Override
    public String toString() {
        return String.format("Driver[id=%d name=%-10s dist=%.2fkm rating=%.1f accept=%.0f%% score=%.1f]",
            driverId, driverName, distanceKm, rating, acceptanceRate * 100, compositeScore);
    }
}
