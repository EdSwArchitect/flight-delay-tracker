package com.bscllc.flightdelays.opensky;

import java.time.Instant;

public record StateVector(
    String icao24,
    String callsign,
    double longitude,
    double latitude,
    double altitude,
    boolean onGround,
    double velocity,
    double heading,
    Instant recordedAt
) {}
