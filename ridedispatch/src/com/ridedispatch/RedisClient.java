package com.ridedispatch;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Minimal Redis client using plain Java sockets + RESP2 protocol.
 * Supports: SET, GET, DEL, GEOADD, GEORADIUS (GEORADIUS_RO), EXISTS, EXPIRE.
 * No external dependency — pure JDK.
 */
public class RedisClient {
    private static final Logger log = Logger.getLogger(RedisClient.class.getName());

    private final String host;
    private final int    port;
    private Socket       socket;
    private OutputStream out;
    private BufferedReader in;

    public RedisClient(String host, int port) throws Exception {
        this.host = host;
        this.port = port;
        connect();
    }

    private void connect() throws Exception {
        socket = new Socket(host, port);
        socket.setKeepAlive(true);
        out = socket.getOutputStream();
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        log.info("Redis connected to " + host + ":" + port);
    }

    private void ensureConnected() {
        try {
            if (socket == null || socket.isClosed()) connect();
        } catch (Exception e) {
            throw new RuntimeException("Redis reconnect failed", e);
        }
    }

    // ── RESP encoding ─────────────────────────────────────────────────────

    private synchronized String send(String... args) {
        ensureConnected();
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append("*").append(args.length).append("\r\n");
            for (String a : args) {
                cmd.append("$").append(a.getBytes().length).append("\r\n");
                cmd.append(a).append("\r\n");
            }
            out.write(cmd.toString().getBytes());
            out.flush();
            return readResponse();
        } catch (Exception e) {
            throw new RuntimeException("Redis command failed: " + Arrays.toString(args), e);
        }
    }

    private String readResponse() throws Exception {
        String line = in.readLine();
        if (line == null) return null;
        char type = line.charAt(0);
        String data = line.substring(1);

        return switch (type) {
            case '+' -> data;                              // Simple string
            case '-' -> throw new RuntimeException("Redis error: " + data);
            case ':' -> data;                              // Integer
            case '$' -> {                                  // Bulk string
                int len = Integer.parseInt(data);
                if (len == -1) yield null;
                char[] buf = new char[len];
                int read = 0;
                while (read < len) read += in.read(buf, read, len - read);
                in.readLine();                             // consume \r\n
                yield new String(buf);
            }
            case '*' -> {                                  // Array — collect into newline-sep string
                int count = Integer.parseInt(data);
                if (count == -1) yield null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String item = readResponse();
                    if (i > 0) sb.append("\n");
                    sb.append(item == null ? "" : item);
                }
                yield sb.toString();
            }
            default -> data;
        };
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void set(String key, String value, int ttlSeconds) {
        send("SET", key, value, "EX", String.valueOf(ttlSeconds));
    }

    public String get(String key) {
        return send("GET", key);
    }

    public void del(String key) {
        send("DEL", key);
    }

    public boolean exists(String key) {
        return "1".equals(send("EXISTS", key));
    }

    /** Store driver GPS position in a Redis Geo sorted set */
    public void geoAdd(String key, long driverId, double lat, double lng) {
        send("GEOADD", key, String.valueOf(lng), String.valueOf(lat), String.valueOf(driverId));
    }

    public void geoRemove(String key, long driverId) {
        send("ZREM", key, String.valueOf(driverId));
    }

    /**
     * Find drivers within radiusKm of (lat,lng).
     * Returns list of [memberId, distKm, lng, lat] per driver.
     * Uses GEORADIUS (Redis ≤6) or GEOSEARCH (Redis 7+) via raw command.
     */
    public List<NearbyDriver> geoRadius(String key, double lat, double lng, double radiusKm, int limit) {
        List<NearbyDriver> result = new ArrayList<>();
        try {
            // GEORADIUS key lng lat radius km WITHCOORD WITHDIST COUNT n ASC
            String raw = send("GEORADIUS", key,
                String.valueOf(lng), String.valueOf(lat),
                String.valueOf(radiusKm), "km",
                "WITHCOORD", "WITHDIST", "COUNT", String.valueOf(limit), "ASC");

            if (raw == null || raw.isBlank()) return result;

            // Response is flat newline-separated: id\ndist\nlng\nlat\n id\ndist...
            // Each driver entry = 4 lines (id, dist, lng, lat)
            String[] tokens = raw.split("\n");
            int i = 0;
            while (i + 3 < tokens.length) {
                try {
                    long   id   = Long.parseLong(tokens[i++].trim());
                    double dist = Double.parseDouble(tokens[i++].trim());
                    double dLng = Double.parseDouble(tokens[i++].trim());
                    double dLat = Double.parseDouble(tokens[i++].trim());
                    result.add(new NearbyDriver(id, dist, dLat, dLng));
                } catch (NumberFormatException e) {
                    i++; // skip malformed token
                }
            }
        } catch (Exception e) {
            log.warning("geoRadius failed: " + e.getMessage());
        }
        return result;
    }

    public record NearbyDriver(long driverId, double distanceKm, double lat, double lng) {}
}
