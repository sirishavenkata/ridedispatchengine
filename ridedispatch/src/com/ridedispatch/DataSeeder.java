package com.ridedispatch;

import java.sql.*;
import java.util.logging.Logger;

/**
 * Seeds 10 realistic drivers into PostgreSQL + publishes their
 * locations into Redis so the dispatch engine has data to work with.
 *
 * Drivers are placed around Koramangala, Bangalore (12.9352°N, 77.6245°E).
 * Distances from the test pickup point (12.9716°N, 77.5946°E) vary from
 * 0.4 km to 4.8 km, with deliberately varied ratings and acceptance rates
 * so you can see the comparator make interesting decisions.
 */
public class DataSeeder {
    private static final Logger log = Logger.getLogger(DataSeeder.class.getName());

    private final Database      db;
    private final DriverService driverService;

    // Each row: name, phone, vehicleType, lat, lng, rating, acceptanceRate
    private static final Object[][] SEED_DATA = {
        {"Ravi Kumar",    "+91-9001", "SEDAN", 12.9680, 77.5990, 4.2, 0.75},
        {"Suresh Nair",   "+91-9002", "SEDAN", 12.9650, 77.6020, 4.9, 0.95},
        {"Mohan Das",     "+91-9003", "SEDAN", 12.9730, 77.5960, 3.8, 0.60},
        {"Priya Sharma",  "+91-9004", "SEDAN", 12.9620, 77.6040, 4.8, 0.90},
        {"Kumar Reddy",   "+91-9005", "SEDAN", 12.9580, 77.5980, 4.5, 0.80},
        {"Anita Singh",   "+91-9006", "SEDAN", 12.9690, 77.5985, 4.7, 0.85},
        {"Vikram Joshi",  "+91-9007", "SEDAN", 12.9450, 77.6100, 4.9, 0.98},
        {"Deepa Rao",     "+91-9008", "SEDAN", 12.9660, 77.6010, 4.6, 0.88},
        {"Auto Driver A", "+91-9009", "AUTO",  12.9700, 77.5950, 4.3, 0.78},
        {"Auto Driver B", "+91-9010", "AUTO",  12.9710, 77.5970, 4.6, 0.82},
    };

    public DataSeeder(Database db, DriverService driverService) {
        this.db            = db;
        this.driverService = driverService;
    }

    public void seed() throws Exception {
        if (alreadySeeded()) {
            log.info("Data already seeded — skipping");
            rePublishLocations();  // re-publish Redis locations on restart
            return;
        }

        log.info("Seeding " + SEED_DATA.length + " drivers...");
        for (Object[] row : SEED_DATA) {
            String name         = (String) row[0];
            String phone        = (String) row[1];
            String vehicleType  = (String) row[2];
            double lat          = (double) row[3];
            double lng          = (double) row[4];
            double rating       = (double) row[5];
            double acceptance   = (double) row[6];

            Driver driver = driverService.create(name, phone, vehicleType, rating, acceptance);
            driverService.updateLocation(driver.id, lat, lng, vehicleType);
            log.info("  Seeded: " + driver.name + " (id=" + driver.id + ") at (" + lat + "," + lng + ")");
        }
        log.info("Seeding complete");
    }

    /** Re-publish Redis locations from DB on restart (Redis is ephemeral) */
    private void rePublishLocations() throws Exception {
        log.info("Re-publishing driver locations to Redis...");
        int idx = 0;
        for (Driver d : driverService.findAll()) {
            if (idx < SEED_DATA.length) {
                double lat = (double) SEED_DATA[idx][3];
                double lng = (double) SEED_DATA[idx][4];
                driverService.updateLocation(d.id, lat, lng, d.vehicleType);
                idx++;
            }
        }
        log.info("Locations re-published");
    }

    private boolean alreadySeeded() throws Exception {
        Connection c = db.borrow();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM drivers")) {
            rs.next();
            return rs.getInt(1) > 0;
        } finally {
            db.release(c);
        }
    }
}
