package com.ridedispatch;

import java.sql.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Database {
    private static final Logger log = Logger.getLogger(Database.class.getName());

    private final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
    private final String url, user, pass;
    private static final int POOL_SIZE = 5;

    public Database(String url, String user, String pass) throws Exception {
        this.url  = url;
        this.user = user;
        this.pass = pass;
        Class.forName("org.postgresql.Driver");
        for (int i = 0; i < POOL_SIZE; i++) pool.offer(newConnection());
        log.info("DB pool ready (" + POOL_SIZE + " connections)");
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(url, user, pass);
    }

    public Connection borrow() throws Exception {
        Connection c = pool.poll(3, TimeUnit.SECONDS);
        if (c == null) throw new RuntimeException("No DB connection available");
        if (!c.isValid(1)) c = newConnection();
        return c;
    }

    public void release(Connection c) {
        if (c != null) pool.offer(c);
    }

    public void initSchema() throws Exception {
        Connection c = borrow();
        try (Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS drivers (
                    id              BIGSERIAL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    phone           VARCHAR(20)  NOT NULL UNIQUE,
                    vehicle_type    VARCHAR(20)  NOT NULL,
                    rating          DOUBLE PRECISION NOT NULL DEFAULT 4.5,
                    acceptance_rate DOUBLE PRECISION NOT NULL DEFAULT 0.85,
                    total_trips     INT NOT NULL DEFAULT 0,
                    status          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
                    created_at      TIMESTAMP DEFAULT NOW()
                )
            """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS rides (
                    id               BIGSERIAL PRIMARY KEY,
                    rider_id         BIGINT NOT NULL,
                    driver_id        BIGINT,
                    pickup_lat       DOUBLE PRECISION NOT NULL,
                    pickup_lng       DOUBLE PRECISION NOT NULL,
                    drop_lat         DOUBLE PRECISION NOT NULL,
                    drop_lng         DOUBLE PRECISION NOT NULL,
                    vehicle_type     VARCHAR(20) NOT NULL,
                    surge_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                    status           VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
                    requested_at     TIMESTAMP DEFAULT NOW(),
                    matched_at       TIMESTAMP
                )
            """);
            log.info("Schema ready");
        } finally {
            release(c);
        }
    }
}
