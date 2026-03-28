package com.bscllc.flightdelays.airlabs;

import java.time.Instant;

public record FlightDelay(
    String flightIata,
    int delayedMin,
    Instant recordedAt
) {}
