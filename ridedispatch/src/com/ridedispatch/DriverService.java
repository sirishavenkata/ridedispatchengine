package com.ridedispatch;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class DriverService {
    private static final Logger log = Logger.getLogger(DriverService.class.getName());

    private static final String GEO_KEY_PREFIX    = "driver:geo:";
    private static final String ACTIVE_KEY_PREFIX = "driver:active:";
    private static final int    LOCATION_TTL      = 30; // seconds

    private final Database    db;
    private final RedisClient redis;

    public DriverService(Database db, RedisClient redis) {
        this.db    = db;
        this.redis = redis;
    }

    // ── Create ────────────────────────────────────────────────────────────

    public Driver create(String name, String phone, String vehicleType,
                         double rating, double acceptanceRate) throws Exception {
        Connection c = db.borrow();
        try {
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO drivers (name, phone, vehicle_type, rating, acceptance_rate, status) " +
                "VALUES (?, ?, ?, ?, ?, 'AVAILABLE') RETURNING id",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, phone);
            ps.setString(3, vehicleType);
            ps.setDouble(4, rating);
            ps.setDouble(5, acceptanceRate);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            rs.next();
            long id = rs.getLong(1);
            log.info("Created driver id=" + id + " name=" + name);
            return new Driver(id, name, phone, vehicleType, rating, acceptanceRate, 0, "AVAILABLE");
        } finally {
            db.release(c);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────

    public List<Driver> findAll() throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM drivers ORDER BY id")) {
            return mapDrivers(ps.executeQuery());
        } finally {
            db.release(c);
        }
    }

    public Optional<Driver> findById(long id) throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM drivers WHERE id = ?")) {
            ps.setLong(1, id);
            List<Driver> list = mapDrivers(ps.executeQuery());
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        } finally {
            db.release(c);
        }
    }

    /** Fetch AVAILABLE drivers by IDs in one query — used by dispatch engine */
    public Map<Long, Driver> findAvailableByIds(List<Long> ids) throws Exception {
        if (ids.isEmpty()) return Map.of();
        Connection c = db.borrow();
        try {
            String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
            PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM drivers WHERE id IN (" + placeholders + ") AND status = 'AVAILABLE'");
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            Map<Long, Driver> map = new HashMap<>();
            mapDrivers(ps.executeQuery()).forEach(d -> map.put(d.id, d));
            return map;
        } finally {
            db.release(c);
        }
    }

    // ── Location (Redis) ──────────────────────────────────────────────────

    /**
     * Driver app calls this every ~10s to update GPS.
     * Stores in Redis Geo set + sets TTL so offline drivers expire automatically.
     */
    public void updateLocation(long driverId, double lat, double lng, String vehicleType) {
        String geoKey    = GEO_KEY_PREFIX + vehicleType;
        String activeKey = ACTIVE_KEY_PREFIX + driverId;
        redis.geoAdd(geoKey, driverId, lat, lng);
        redis.set(activeKey, "1", LOCATION_TTL);
        log.fine("Location updated: driver " + driverId + " → (" + lat + "," + lng + ")");
    }

    public boolean isDriverActive(long driverId) {
        return redis.exists(ACTIVE_KEY_PREFIX + driverId);
    }

    /** Find nearby drivers via Redis geo search */
    public List<RedisClient.NearbyDriver> findNearbyDrivers(
            double lat, double lng, double radiusKm, String vehicleType, int limit) {
        String geoKey = GEO_KEY_PREFIX + vehicleType;
        List<RedisClient.NearbyDriver> all = redis.geoRadius(geoKey, lat, lng, radiusKm, limit);
        // Filter out expired (offline) drivers
        return all.stream().filter(nd -> isDriverActive(nd.driverId())).toList();
    }

    // ── Status update ─────────────────────────────────────────────────────

    public void setStatus(long driverId, String status) throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE drivers SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, driverId);
            ps.executeUpdate();
        } finally {
            db.release(c);
        }
    }

    // ── Mapping helper ────────────────────────────────────────────────────

    private List<Driver> mapDrivers(ResultSet rs) throws SQLException {
        List<Driver> list = new ArrayList<>();
        while (rs.next()) {
            list.add(new Driver(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("vehicle_type"),
                rs.getDouble("rating"),
                rs.getDouble("acceptance_rate"),
                rs.getInt("total_trips"),
                rs.getString("status")
            ));
        }
        return list;
    }
}
