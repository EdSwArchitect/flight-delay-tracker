package com.bscllc.flightdelays.join;

import java.util.Map;

/**
 * Resolves ICAO callsigns to IATA flight identifiers using a 3-step chain:
 * 1. Exact match against callsign index
 * 2. ICAO 3-letter prefix to IATA 2-letter prefix normalisation
 * 3. Fallback (no match)
 */
public class CallsignResolver {

    static final Map<String, String> ICAO_TO_IATA = Map.ofEntries(
        Map.entry("AAL", "AA"), Map.entry("BAW", "BA"), Map.entry("DAL", "DL"),
        Map.entry("UAL", "UA"), Map.entry("SWA", "WN"), Map.entry("AWE", "US"),
        Map.entry("FFT", "F9"), Map.entry("JBU", "B6"), Map.entry("SKW", "OO"),
        Map.entry("ENY", "MQ")
    );

    public enum Result { RESOLVED, POSITION_ONLY, UNRESOLVABLE }

    public record Resolution(Result result, String matchedKey, String reason) {}

    /**
     * Attempt to resolve a callsign against the given index.
     *
     * @param callsign      stripped callsign from the StateVector
     * @param callsignIndex map of callsign -> schedule JSON from Redis
     * @return Resolution indicating the outcome
     */
    public Resolution resolve(String callsign, Map<String, String> callsignIndex) {
        if (callsign == null || callsign.isEmpty()) {
            return new Resolution(Result.UNRESOLVABLE, null, "blank_callsign");
        }

        // Step 1: Exact match
        if (callsignIndex.containsKey(callsign)) {
            return new Resolution(Result.RESOLVED, callsign, null);
        }

        // Step 2: ICAO prefix normalisation
        if (callsign.length() >= 4) {
            String prefix3 = callsign.substring(0, 3).toUpperCase();
            String iataPrefix = ICAO_TO_IATA.get(prefix3);
            if (iataPrefix != null) {
                String iataCallsign = iataPrefix + callsign.substring(3).replaceFirst("^0+", "");
                if (callsignIndex.containsKey(iataCallsign)) {
                    return new Resolution(Result.RESOLVED, iataCallsign, null);
                }
                return new Resolution(Result.POSITION_ONLY, null, "normalisation_miss");
            }
        }

        // Step 3: Fallback
        return new Resolution(Result.POSITION_ONLY, null, "no_match");
    }

    /**
     * Normalise an ICAO callsign to its IATA equivalent, if a mapping exists.
     * Returns null if no mapping found.
     */
    public String normaliseToIata(String icaoCallsign) {
        if (icaoCallsign == null || icaoCallsign.length() < 4) return null;
        String prefix3 = icaoCallsign.substring(0, 3).toUpperCase();
        String iataPrefix = ICAO_TO_IATA.get(prefix3);
        if (iataPrefix == null) return null;
        return iataPrefix + icaoCallsign.substring(3).replaceFirst("^0+", "");
    }
}
