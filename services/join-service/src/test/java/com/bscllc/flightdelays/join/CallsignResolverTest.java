package com.bscllc.flightdelays.join;

import com.bscllc.flightdelays.join.CallsignResolver.Resolution;
import com.bscllc.flightdelays.join.CallsignResolver.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CallsignResolverTest {

    private CallsignResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CallsignResolver();
    }

    @Nested
    @DisplayName("Step 1 - Exact match")
    class ExactMatchTests {

        @Test
        @DisplayName("exactMatch_returnsResolved")
        void exactMatch_returnsResolved() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("AA100", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("AA100", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("exactMatch_iataCallsign")
        void exactMatch_iataCallsign() {
            Map<String, String> index = Map.of("DL456", "scheduled-data");
            Resolution resolution = resolver.resolve("DL456", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("DL456", resolution.matchedKey());
            assertNull(resolution.reason());
        }
    }

    @Nested
    @DisplayName("Step 2 - ICAO prefix normalisation")
    class IcaoPrefixNormalisationTests {

        @Test
        @DisplayName("icaoPrefix_AAL_resolvesToAA")
        void icaoPrefix_AAL_resolvesToAA() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("AAL100", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("AA100", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_BAW_resolvesToBA")
        void icaoPrefix_BAW_resolvesToBA() {
            Map<String, String> index = Map.of("BA456", "scheduled-data");
            Resolution resolution = resolver.resolve("BAW456", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("BA456", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_DAL_resolvesToDL")
        void icaoPrefix_DAL_resolvesToDL() {
            Map<String, String> index = Map.of("DL789", "scheduled-data");
            Resolution resolution = resolver.resolve("DAL789", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("DL789", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_UAL_resolvesToUA")
        void icaoPrefix_UAL_resolvesToUA() {
            Map<String, String> index = Map.of("UA100", "scheduled-data");
            Resolution resolution = resolver.resolve("UAL100", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("UA100", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_SWA_resolvesToWN")
        void icaoPrefix_SWA_resolvesToWN() {
            Map<String, String> index = Map.of("WN1234", "scheduled-data");
            Resolution resolution = resolver.resolve("SWA1234", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("WN1234", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_JBU_resolvesToB6")
        void icaoPrefix_JBU_resolvesToB6() {
            Map<String, String> index = Map.of("B6567", "scheduled-data");
            Resolution resolution = resolver.resolve("JBU567", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("B6567", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_stripsLeadingZeros")
        void icaoPrefix_stripsLeadingZeros() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("AAL0100", index);

            assertEquals(Result.RESOLVED, resolution.result());
            assertEquals("AA100", resolution.matchedKey());
            assertNull(resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_unknownPrefix_returnsNoMatch")
        void icaoPrefix_unknownPrefix_returnsNoMatch() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("XYZ100", index);

            assertEquals(Result.POSITION_ONLY, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("no_match", resolution.reason());
        }

        @Test
        @DisplayName("icaoPrefix_knownPrefixButNotInIndex")
        void icaoPrefix_knownPrefixButNotInIndex() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("AAL999", index);

            assertEquals(Result.POSITION_ONLY, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("normalisation_miss", resolution.reason());
        }
    }

    @Nested
    @DisplayName("Step 3 - Fallback")
    class FallbackTests {

        @Test
        @DisplayName("blankCallsign_returnsUnresolvable")
        void blankCallsign_returnsUnresolvable() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("", index);

            assertEquals(Result.UNRESOLVABLE, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("blank_callsign", resolution.reason());
        }

        @Test
        @DisplayName("nullCallsign_returnsUnresolvable")
        void nullCallsign_returnsUnresolvable() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve(null, index);

            assertEquals(Result.UNRESOLVABLE, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("blank_callsign", resolution.reason());
        }

        @Test
        @DisplayName("noMatch_returnsPositionOnly")
        void noMatch_returnsPositionOnly() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("UNKNOWN1", index);

            assertEquals(Result.POSITION_ONLY, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("no_match", resolution.reason());
        }

        @Test
        @DisplayName("shortCallsign_noNormalisation")
        void shortCallsign_noNormalisation() {
            Map<String, String> index = Map.of("AA100", "scheduled-data");
            Resolution resolution = resolver.resolve("AB", index);

            assertEquals(Result.POSITION_ONLY, resolution.result());
            assertNull(resolution.matchedKey());
            assertEquals("no_match", resolution.reason());
        }
    }

    @Nested
    @DisplayName("normaliseToIata")
    class NormaliseToIataTests {

        @Test
        @DisplayName("normalise_AAL100_toAA100")
        void normalise_AAL100_toAA100() {
            String result = resolver.normaliseToIata("AAL100");

            assertNotNull(result);
            assertEquals("AA100", result);
        }

        @Test
        @DisplayName("normalise_DAL0042_toDL42")
        void normalise_DAL0042_toDL42() {
            String result = resolver.normaliseToIata("DAL0042");

            assertNotNull(result);
            assertEquals("DL42", result);
        }

        @Test
        @DisplayName("normalise_unknownPrefix_returnsNull")
        void normalise_unknownPrefix_returnsNull() {
            String result = resolver.normaliseToIata("XYZ100");

            assertNull(result);
        }

        @Test
        @DisplayName("normalise_nullInput_returnsNull")
        void normalise_nullInput_returnsNull() {
            String result = resolver.normaliseToIata(null);

            assertNull(result);
        }

        @Test
        @DisplayName("normalise_shortInput_returnsNull")
        void normalise_shortInput_returnsNull() {
            String result = resolver.normaliseToIata("AB");

            assertNull(result);
        }
    }
}
