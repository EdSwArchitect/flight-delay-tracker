package com.bscllc.flightdelays.airlabs;

public record FlightSchedule(
    String flightIata,
    String depIata,
    String arrIata,
    String depTime,
    String arrTime,
    String depEstimated,
    String arrEstimated,
    String status,
    Integer delayedMin
) {}
