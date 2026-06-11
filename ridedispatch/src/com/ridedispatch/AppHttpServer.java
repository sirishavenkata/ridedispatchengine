package com.ridedispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.logging.Logger;

/**
 * REST API using JDK built-in HTTP server (no Tomcat / Jetty needed).
 *
 * Endpoints:
 *   GET  /health                          — liveness check
 *   GET  /api/drivers                     — list all drivers
 *   POST /api/drivers                     — create a driver
 *   PUT  /api/drivers/{id}/location       — update driver GPS (simulates mobile app)
 *   POST /api/rides                       — request a ride → triggers PQ dispatch
 *   GET  /api/rides                       — list all rides
 *   GET  /api/dispatch/simulate           — run a demo dispatch and return ranked candidates
 */
public class AppHttpServer {
    private static final Logger log = Logger.getLogger(AppHttpServer.class.getName());

    private final int           port;
    private final DriverService driverService;
    private final RideService   rideService;
    private final ObjectMapper  json = new ObjectMapper();

    public AppHttpServer(int port, DriverService driverService, RideService rideService) {
        this.port          = port;
        this.driverService = driverService;
        this.rideService   = rideService;
    }

    public void start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health",              this::handleHealth);
        server.createContext("/api/drivers",         this::handleDrivers);
        server.createContext("/api/rides",           this::handleRides);
        server.createContext("/api/dispatch/simulate", this::handleSimulate);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        server.start();
        log.info("Server started on http://localhost:" + port);
        log.info("  GET  /health");
        log.info("  GET  /api/drivers");
        log.info("  POST /api/drivers");
        log.info("  PUT  /api/drivers/{id}/location?lat=&lng=");
        log.info("  POST /api/rides");
        log.info("  GET  /api/rides");
        log.info("  GET  /api/dispatch/simulate");
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, Map.of("status", "UP", "service", "ride-dispatch"));
    }

    private void handleDrivers(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path   = ex.getRequestURI().getPath();

        try {
            // PUT /api/drivers/{id}/location
            if ("PUT".equals(method) && path.matches("/api/drivers/\\d+/location")) {
                long id = Long.parseLong(path.split("/")[3]);
                Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
                double lat = Double.parseDouble(params.getOrDefault("lat", "0"));
                double lng = Double.parseDouble(params.getOrDefault("lng", "0"));
                String vtype = params.getOrDefault("vehicleType", "SEDAN");
                driverService.updateLocation(id, lat, lng, vtype);
                respond(ex, 200, Map.of("message", "Location updated", "driverId", id));
                return;
            }

            // POST /api/drivers
            if ("POST".equals(method) && "/api/drivers".equals(path)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(readBody(ex), Map.class);
                Driver d = driverService.create(
                    (String) body.get("name"),
                    (String) body.get("phone"),
                    (String) body.getOrDefault("vehicleType", "SEDAN"),
                    ((Number) body.getOrDefault("rating", 4.5)).doubleValue(),
                    ((Number) body.getOrDefault("acceptanceRate", 0.85)).doubleValue()
                );
                respond(ex, 201, d);
                return;
            }

            // GET /api/drivers
            if ("GET".equals(method)) {
                respond(ex, 200, driverService.findAll());
                return;
            }

            respond(ex, 405, Map.of("error", "Method not allowed"));
        } catch (Exception e) {
            log.severe("Error in /api/drivers: " + e.getMessage());
            respond(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleRides(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        try {
            if ("POST".equals(method)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(readBody(ex), Map.class);
                Map<String, Object> result = rideService.requestRide(
                    ((Number) body.getOrDefault("riderId", 1)).longValue(),
                    ((Number) body.get("pickupLat")).doubleValue(),
                    ((Number) body.get("pickupLng")).doubleValue(),
                    ((Number) body.get("dropLat")).doubleValue(),
                    ((Number) body.get("dropLng")).doubleValue(),
                    (String)  body.getOrDefault("vehicleType", "SEDAN"),
                    ((Number) body.getOrDefault("surgeMultiplier", 1.0)).doubleValue()
                );
                int status = "MATCHED".equals(result.get("status")) ? 200 : 503;
                respond(ex, status, result);
                return;
            }

            if ("GET".equals(method)) {
                respond(ex, 200, rideService.getAllRides());
                return;
            }

            respond(ex, 405, Map.of("error", "Method not allowed"));
        } catch (Exception e) {
            log.severe("Error in /api/rides: " + e.getMessage());
            respond(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    /**
     * Demo endpoint — shows ALL candidates ranked by the PQ comparator
     * without actually creating a ride. Great for understanding how scoring works.
     */
    private void handleSimulate(HttpExchange ex) throws IOException {
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            double pickupLat = Double.parseDouble(params.getOrDefault("lat", "12.9716"));
            double pickupLng = Double.parseDouble(params.getOrDefault("lng", "77.5946"));
            String vtype     = params.getOrDefault("vehicleType", "SEDAN");
            double surge     = Double.parseDouble(params.getOrDefault("surge", "1.0"));

            List<RedisClient.NearbyDriver> nearby =
                driverService.findNearbyDrivers(pickupLat, pickupLng, 5.0, vtype, 20);

            List<Long> ids = nearby.stream().map(RedisClient.NearbyDriver::driverId).toList();
            Map<Long, Driver> profiles = driverService.findAvailableByIds(ids);

            DriverScoreComparator comparator = new DriverScoreComparator();
            PriorityQueue<DriverCandidate> pq = new PriorityQueue<>(
                Math.max(1, profiles.size()), comparator);

            for (RedisClient.NearbyDriver nd : nearby) {
                Driver p = profiles.get(nd.driverId());
                if (p == null) continue;
                DriverCandidate c = new DriverCandidate(
                    p.id, p.name, p.vehicleType,
                    p.rating, p.acceptanceRate, p.totalTrips,
                    nd.distanceKm(), nd.lat(), nd.lng(), surge);
                c.compositeScore = DriverScoreComparator.computeScore(c);
                pq.offer(c);
            }

            List<Map<String, Object>> ranked = new ArrayList<>();
            int rank = 1;
            while (!pq.isEmpty()) {
                DriverCandidate c = pq.poll();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank",           rank++);
                row.put("driverId",       c.driverId);
                row.put("name",           c.driverName);
                row.put("distanceKm",     Math.round(c.distanceKm * 100.0) / 100.0);
                row.put("rating",         c.rating);
                row.put("acceptanceRate", c.acceptanceRate);
                row.put("surgeMultiplier",c.surgeMultiplier);
                row.put("compositeScore", Math.round(c.compositeScore * 10.0) / 10.0);
                row.put("wouldBeMatched", rank == 2); // rank was already incremented
                ranked.add(row);
            }
            if (!ranked.isEmpty()) {
                ranked.get(0).put("wouldBeMatched", true);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("pickupLat",    pickupLat);
            resp.put("pickupLng",    pickupLng);
            resp.put("vehicleType",  vtype);
            resp.put("surge",        surge);
            resp.put("totalFound",   ranked.size());
            resp.put("rankedDrivers",ranked);
            respond(ex, 200, resp);
        } catch (Exception e) {
            log.severe("Simulate error: " + e.getMessage());
            respond(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void respond(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes());
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }
}
