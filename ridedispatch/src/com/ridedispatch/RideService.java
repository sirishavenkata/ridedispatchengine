package com.ridedispatch;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class RideService {
    private static final Logger log = Logger.getLogger(RideService.class.getName());

    private final Database       db;
    private final DispatchService dispatch;

    public RideService(Database db, DispatchService dispatch) {
        this.db       = db;
        this.dispatch = dispatch;
    }

    public Map<String, Object> requestRide(long riderId,
                                            double pickupLat, double pickupLng,
                                            double dropLat,   double dropLng,
                                            String vehicleType,
                                            double surgeMultiplier) throws Exception {
        // 1. Persist ride
        long rideId = createRide(riderId, pickupLat, pickupLng,
                                  dropLat, dropLng, vehicleType, surgeMultiplier);

        // 2. Dispatch — PriorityQueue logic runs here
        DispatchService.DispatchResult result =
            dispatch.dispatch(rideId, pickupLat, pickupLng, vehicleType, surgeMultiplier);

        // 3. Build response
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rideId",  rideId);
        resp.put("status",  result.matched() ? "MATCHED" : "NO_DRIVER_FOUND");
        resp.put("message", result.message());

        if (result.matched() && result.driver() != null) {
            DriverCandidate d = result.driver();
            Map<String, Object> driverInfo = new LinkedHashMap<>();
            driverInfo.put("driverId",      d.driverId);
            driverInfo.put("name",          d.driverName);
            driverInfo.put("rating",        d.rating);
            driverInfo.put("distanceKm",    Math.round(d.distanceKm * 100.0) / 100.0);
            driverInfo.put("score",         Math.round(d.compositeScore * 10.0) / 10.0);
            driverInfo.put("acceptRate",    d.acceptanceRate);
            resp.put("driver", driverInfo);
        }
        return resp;
    }

    public List<Map<String, Object>> getAllRides() throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM rides ORDER BY id DESC LIMIT 50")) {
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",              rs.getLong("id"));
                m.put("riderId",         rs.getLong("rider_id"));
                m.put("driverId",        rs.getObject("driver_id"));
                m.put("status",          rs.getString("status"));
                m.put("vehicleType",     rs.getString("vehicle_type"));
                m.put("surgeMultiplier", rs.getDouble("surge_multiplier"));
                m.put("pickupLat",       rs.getDouble("pickup_lat"));
                m.put("pickupLng",       rs.getDouble("pickup_lng"));
                m.put("requestedAt",     rs.getTimestamp("requested_at") + "");
                Timestamp matched = rs.getTimestamp("matched_at");
                m.put("matchedAt", matched != null ? matched.toString() : null);
                list.add(m);
            }
            return list;
        } finally {
            db.release(c);
        }
    }

    private long createRide(long riderId, double pickupLat, double pickupLng,
                             double dropLat, double dropLng,
                             String vehicleType, double surge) throws Exception {
        Connection c = db.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rides (rider_id, pickup_lat, pickup_lng, drop_lat, drop_lng, " +
                "vehicle_type, surge_multiplier, status) VALUES (?,?,?,?,?,?,?,'DISPATCHING') RETURNING id")) {
            ps.setLong(1, riderId);
            ps.setDouble(2, pickupLat);
            ps.setDouble(3, pickupLng);
            ps.setDouble(4, dropLat);
            ps.setDouble(5, dropLng);
            ps.setString(6, vehicleType);
            ps.setDouble(7, surge);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long id = rs.getLong(1);
            log.info("Created rideId=" + id);
            return id;
        } finally {
            db.release(c);
        }
    }
}
