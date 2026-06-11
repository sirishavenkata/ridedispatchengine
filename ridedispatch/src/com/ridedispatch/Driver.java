package com.ridedispatch;

public class Driver {
    public long   id;
    public String name;
    public String phone;
    public String vehicleType;
    public double rating;
    public double acceptanceRate;
    public int    totalTrips;
    public String status;         // AVAILABLE, ON_TRIP, OFFLINE

    public Driver() {}

    public Driver(long id, String name, String phone, String vehicleType,
                  double rating, double acceptanceRate, int totalTrips, String status) {
        this.id             = id;
        this.name           = name;
        this.phone          = phone;
        this.vehicleType    = vehicleType;
        this.rating         = rating;
        this.acceptanceRate = acceptanceRate;
        this.totalTrips     = totalTrips;
        this.status         = status;
    }
}
