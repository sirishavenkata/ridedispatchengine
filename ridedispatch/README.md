# Ride Dispatch Engine

Priority Queue–powered driver dispatch system.  
Stack: **Java 21** · **PostgreSQL 16** · **Redis 7** · **Docker Compose**

---

## Quick start (Docker — recommended)

```bash
# 1. Build the jar once
chmod +x build.sh && ./build.sh

# 2. Start everything (Postgres + Redis + App)
docker compose up --build

# 3. App is live at http://localhost:8080
```

## Quick start (local Java + Docker infra)

```bash
chmod +x build.sh run-local.sh
./build.sh          # compile → ride-dispatch.jar
./run-local.sh      # starts Postgres+Redis via Docker, runs jar locally
```

---

## API reference & test commands

### Health check
```bash
curl http://localhost:8080/health
```

---

### List all seeded drivers
```bash
curl http://localhost:8080/api/drivers | python3 -m json.tool
```

---

### Update a driver's GPS location
```bash
# Driver 1 at Koramangala
curl -X PUT "http://localhost:8080/api/drivers/1/location?lat=12.9680&lng=77.5990&vehicleType=SEDAN"

# Driver 2 at Indiranagar
curl -X PUT "http://localhost:8080/api/drivers/2/location?lat=12.9716&lng=77.6412&vehicleType=SEDAN"
```

---

### Request a ride (triggers PriorityQueue dispatch)
```bash
curl -X POST http://localhost:8080/api/rides \
  -H "Content-Type: application/json" \
  -d '{
    "riderId": 101,
    "pickupLat": 12.9716,
    "pickupLng": 77.5946,
    "dropLat":   12.9352,
    "dropLng":   77.6245,
    "vehicleType": "SEDAN",
    "surgeMultiplier": 1.0
  }'
```

**Example response:**
```json
{
  "rideId": 1,
  "status": "MATCHED",
  "message": "Driver Suresh Nair matched! ETA: 4 mins",
  "driver": {
    "driverId": 2,
    "name": "Suresh Nair",
    "rating": 4.9,
    "distanceKm": 1.2,
    "score": 81.2,
    "acceptRate": 0.95
  }
}
```

---

### Request a ride with surge pricing (changes ranking!)
```bash
curl -X POST http://localhost:8080/api/rides \
  -H "Content-Type: application/json" \
  -d '{
    "riderId": 102,
    "pickupLat": 12.9716,
    "pickupLng": 77.5946,
    "dropLat":   12.9352,
    "dropLng":   77.6245,
    "vehicleType": "SEDAN",
    "surgeMultiplier": 3.0
  }'
```

---

### Request an AUTO ride
```bash
curl -X POST http://localhost:8080/api/rides \
  -H "Content-Type: application/json" \
  -d '{
    "riderId": 103,
    "pickupLat": 12.9716,
    "pickupLng": 77.5946,
    "dropLat":   12.9352,
    "dropLng":   77.6245,
    "vehicleType": "AUTO",
    "surgeMultiplier": 1.0
  }'
```

---

### Simulate dispatch (see full ranked candidate list — best feature for learning!)
```bash
# No surge
curl "http://localhost:8080/api/dispatch/simulate?lat=12.9716&lng=77.5946&vehicleType=SEDAN&surge=1.0" \
  | python3 -m json.tool

# With 3x surge — notice how rankings change
curl "http://localhost:8080/api/dispatch/simulate?lat=12.9716&lng=77.5946&vehicleType=SEDAN&surge=3.0" \
  | python3 -m json.tool
```

**Example simulate response:**
```json
{
  "totalFound": 8,
  "rankedDrivers": [
    { "rank": 1, "name": "Suresh Nair",  "distanceKm": 1.2, "rating": 4.9, "compositeScore": 81.2, "wouldBeMatched": true },
    { "rank": 2, "name": "Anita Singh",  "distanceKm": 0.9, "rating": 4.7, "compositeScore": 80.1 },
    { "rank": 3, "name": "Deepa Rao",    "distanceKm": 1.1, "rating": 4.6, "compositeScore": 78.3 },
    { "rank": 4, "name": "Priya Sharma", "distanceKm": 1.5, "rating": 4.8, "compositeScore": 77.0 },
    { "rank": 5, "name": "Ravi Kumar",   "distanceKm": 0.8, "rating": 4.2, "compositeScore": 75.1 },
    { "rank": 6, "name": "Mohan Das",    "distanceKm": 0.3, "rating": 3.8, "compositeScore": 73.1 },
    { "rank": 7, "name": "Kumar Reddy",  "distanceKm": 2.1, "rating": 4.5, "compositeScore": 68.0 },
    { "rank": 8, "name": "Vikram Joshi", "distanceKm": 3.5, "rating": 4.9, "compositeScore": 63.4 }
  ]
}
```

---

### List all rides
```bash
curl http://localhost:8080/api/rides | python3 -m json.tool
```

---

### Create a new driver manually
```bash
curl -X POST http://localhost:8080/api/drivers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Driver",
    "phone": "+91-9999",
    "vehicleType": "SEDAN",
    "rating": 4.7,
    "acceptanceRate": 0.92
  }'
```

---

## How the PriorityQueue comparator works

```
Score formula (weights in DriverScoreComparator.java):
  distanceScore  (0–100) = (1 - dist/5km) × 100     weight: 40%
  ratingScore    (0–100) = (rating - 1) / 4 × 100   weight: 30%
  acceptScore    (0–100) = acceptanceRate × 100       weight: 20%
  surgeScore     (0–100) = (surge - 1) / 2 × 100     weight: 10%

composite = sum of (score × weight)
```

The PQ is a **max-heap** — the driver with the **highest composite score** is
polled first and matched to the rider.

Key insight: Mohan (0.3km, closest) scores **73.1** and Suresh (1.2km) scores
**81.2** because Suresh's 4.9 rating and 95% acceptance rate compensate for
the extra distance. Distance matters but isn't everything.

---

## Project structure

```
ride-dispatch/
├── build.sh                      # compile → fat jar
├── run-local.sh                  # start infra + run jar
├── docker-compose.yml            # Postgres + Redis + App
├── Dockerfile                    # container for the app
├── ride-dispatch.jar             # pre-built fat jar (after build.sh)
└── src/com/ridedispatch/
    ├── Main.java                 # entry point, wiring
    ├── Database.java             # JDBC connection pool
    ├── RedisClient.java          # RESP2 client (pure Java sockets)
    ├── Driver.java               # model
    ├── DriverCandidate.java      # object inside the PriorityQueue
    ├── DriverScoreComparator.java # THE custom comparator ← core logic
    ├── DriverService.java        # driver CRUD + Redis geo
    ├── DispatchService.java      # builds PQ, runs dispatch
    ├── RideService.java          # ride lifecycle
    ├── DataSeeder.java           # 10 test drivers on startup
    └── AppHttpServer.java        # REST API (JDK built-in httpserver)
```
