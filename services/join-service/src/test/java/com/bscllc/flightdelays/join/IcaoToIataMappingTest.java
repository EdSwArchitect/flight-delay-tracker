package com.bscllc.flightdelays.join;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IcaoToIataMappingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("ICAO-to-IATA mapping is correct for all 10 entries")
    @CsvSource({
        "AAL, AA",
        "BAW, BA",
        "DAL, DL",
        "UAL, UA",
        "SWA, WN",
        "AWE, US",
        "FFT, F9",
        "JBU, B6",
        "SKW, OO",
        "ENY, MQ"
    })
    void icaoToIataMapping_isCorrect(String icaoPrefix, String expectedIata) {
        String actualIata = CallsignResolver.ICAO_TO_IATA.get(icaoPrefix);

        assertNotNull(actualIata, "Mapping for ICAO prefix " + icaoPrefix + " should exist");
        assertEquals(expectedIata, actualIata, "ICAO prefix " + icaoPrefix + " should map to " + expectedIata);
    }

    @Test
    @DisplayName("ICAO-to-IATA map contains exactly 10 entries")
    void icaoToIataMap_hasExactly10Entries() {
        assertEquals(10, CallsignResolver.ICAO_TO_IATA.size(),
            "ICAO_TO_IATA map should contain exactly 10 entries");
    }
}
