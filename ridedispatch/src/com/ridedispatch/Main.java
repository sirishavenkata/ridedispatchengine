package com.ridedispatch;

import java.util.logging.*;

/**
 * Startup entry point.
 *
 * Config via environment variables (with defaults for local dev):
 *   DB_URL      jdbc:postgresql://localhost:5432/dispatch_db
 *   DB_USER     dispatch_user
 *   DB_PASS     dispatch_pass
 *   REDIS_HOST  localhost
 *   REDIS_PORT  6379
 *   SERVER_PORT 8080
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // ── Logging setup ──────────────────────────────────────────────────
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "%1$tH:%1$tM:%1$tS.%1$tL [%4$s] %5$s%6$s%n");
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler h : root.getHandlers()) h.setFormatter(new SimpleFormatter());

        Logger log = Logger.getLogger(Main.class.getName());
        log.info("=== Ride Dispatch Engine starting ===");

        // ── Config ─────────────────────────────────────────────────────────
        String dbUrl    = env("DB_URL",      "jdbc:postgresql://localhost:5432/dispatch_db");
        String dbUser   = env("DB_USER",     "dispatch_user");
        String dbPass   = env("DB_PASS",     "dispatch_pass");
        String redisHost= env("REDIS_HOST",  "localhost");
        int    redisPort= Integer.parseInt(env("REDIS_PORT", "6379"));
        int    httpPort = Integer.parseInt(env("SERVER_PORT","8080"));

        // ── Wire up ────────────────────────────────────────────────────────
        log.info("Connecting to PostgreSQL: " + dbUrl);
        Database db = new Database(dbUrl, dbUser, dbPass);
        db.initSchema();

        log.info("Connecting to Redis: " + redisHost + ":" + redisPort);
        RedisClient redis = new RedisClient(redisHost, redisPort);

        DriverService   driverService  = new DriverService(db, redis);
        DispatchService dispatchService = new DispatchService(db, driverService);
        RideService     rideService    = new RideService(db, dispatchService);

        // ── Seed test data ─────────────────────────────────────────────────
        new DataSeeder(db, driverService).seed();

        // ── Start HTTP server ──────────────────────────────────────────────
        new AppHttpServer(httpPort, driverService, rideService).start();

        log.info("=== Ready! Try: curl http://localhost:" + httpPort + "/health ===");
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
