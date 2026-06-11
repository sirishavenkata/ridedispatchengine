package com.ridedispatch;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * DispatchService — orchestrates driver matching using a PriorityQueue.
 *
 * Flow per ride request:
 *   1. Redis GEORADIUS  → nearby driver IDs + distances
 *   2. PostgreSQL IN(?) → driver profiles for those IDs
 *   3. Build DriverCandidate for each, pre-compute composite score
 *   4. Load all into PriorityQueue<DriverCandidate>(comparator)
 *   5. poll() → best driver
 *   6. Write match to PostgreSQL (ride.driver_id, ride.status, driver.status)
 */
public class DispatchService {
    private static final Logger log = Logger.getLogger(DispatchService.class.getName());

    private static final double SEARCH_RADIUS_KM = 5.0;
    private static final int    MAX_CANDIDATES   = 20;

    private final Database      db;
    private final DriverService driverService;

    public DispatchService(Database db, DriverService driverService) {
        this.db            = db;
        this.driverService = driverService;
    }

    public DispatchResult dispatch(long rideId, double pickupLat, double pickupLng,
                                   String vehicleType, double surgeMultiplier) throws Exception {
        log.info("Dispatching rideId=" + rideId + " vtype=" + vehicleType + " surge=" + surgeMultiplier);

        // ── Step 1: Redis geo search ───────────────────────────────────────
        List<RedisClient.NearbyDriver> nearby = driverService.findNearbyDrivers(
            pickupLat, pickupLng, SEARCH_RADIUS_KM, vehicleType, MAX_CANDIDATES);

        if (nearby.isEmpty()) {
            updateRideStatus(rideId, "NO_DRIVER_FOUND", null);
            log.warning("No nearby drivers in Redis for ride " + rideId);
            return new DispatchResult(false, null, "No drivers available nearby");
        }
        log.info("Redis returned " + nearby.size() + " nearby drivers for ride " + rideId);

        // ── Step 2: PostgreSQL fetch (one query for all candidates) ────────
        List<Long> ids = nearby.stream().map(RedisClient.NearbyDriver::driverId).toList();
        Map<Long, Driver> profiles = driverService.findAvailableByIds(ids);

        if (profiles.isEmpty()) {
            updateRideStatus(rideId, "NO_DRIVER_FOUND", null);
            log.warning("No AVAILABLE drivers in DB for ride " + rideId);
            return new DispatchResult(false, null, "No available drivers");
        }
        log.info("PostgreSQL returned " + profiles.size() + " AVAILABLE profiles");

        // ── Step 3+4: Build candidates and load PriorityQueue ──────────────
        //
        //  *** THIS IS THE CUSTOM COMPARATOR IN ACTION ***
        //  Max-heap: poll() returns the HIGHEST scoring driver first
        //
        DriverScoreComparator comparator = new DriverScoreComparator();
        PriorityQueue<DriverCandidate> pq = new PriorityQueue<>(profiles.size(), comparator);

        for (RedisClient.NearbyDriver nd : nearby) {
            Driver profile = profiles.get(nd.driverId());
            if (profile == null) continue;                 // not AVAILABLE in DB

            DriverCandidate candidate = new DriverCandidate(
                profile.id, profile.name, profile.vehicleType,
                profile.rating, profile.acceptanceRate, profile.totalTrips,
                nd.distanceKm(), nd.lat(), nd.lng(),
                surgeMultiplier
            );

            // Pre-compute score once — not inside compare()
            candidate.compositeScore = DriverScoreComparator.computeScore(candidate);
            pq.offer(candidate);
        }

        if (pq.isEmpty()) {
            updateRideStatus(rideId, "NO_DRIVER_FOUND", null);
            return new DispatchResult(false, null, "No candidates after scoring");
        }

        // ── Log all candidates ranked for observability ────────────────────
        log.info("PQ contents (ranked) for ride " + rideId + ":");
        List<DriverCandidate> allCandidates = new ArrayList<>(pq);
        allCandidates.sort(comparator);
        for (int i = 0; i < allCandidates.size(); i++) {
            log.info("  #" + (i + 1) + " " + allCandidates.get(i));
        }

        // ── Step 5: Poll best driver ───────────────────────────────────────
        DriverCandidate best = pq.poll();
        log.info("MATCHED ride " + rideId + " → " + best);

        // ── Step 6: Persist match in PostgreSQL ────────────────────────────
        matchRide(rideId, best.driverId);
        driverService.setStatus(best.driverId, "ON_TRIP");

        String eta = estimateEta(best.distanceKm);
        return new DispatchResult(true, best,
            "Driver " + best.driverName + " matched! ETA: " + eta);
    }

    private void matchRide(long rideId, long driverId) throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rides SET driver_id=?, status='MATCHED', matched_at=NOW() WHERE id=?")) {
            ps.setLong(1, driverId);
            ps.setLong(2, rideId);
            ps.executeUpdate();
        } finally {
            db.release(c);
        }
    }

    private void updateRideStatus(long rideId, String status, Long driverId) throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rides SET status=?, driver_id=? WHERE id=?")) {
            ps.setString(1, status);
            if (driverId != null) ps.setLong(2, driverId); else ps.setNull(2, Types.BIGINT);
            ps.setLong(3, rideId);
            ps.executeUpdate();
        } finally {
            db.release(c);
        }
    }

    private String estimateEta(double distKm) {
        int mins = (int) Math.ceil(distKm / 20.0 * 60);
        return mins + " min" + (mins == 1 ? "" : "s");
    }

    public record DispatchResult(boolean matched, DriverCandidate driver, String message) {}
}
