-- Flight Delay Tracker — Postgres schema
-- Applied by an init container or manual kubectl exec

CREATE TABLE IF NOT EXISTS flight_schedules (
    flight_iata TEXT NOT NULL,
    dep_iata TEXT,
    arr_iata TEXT,
    dep_time TIMESTAMPTZ NOT NULL,
    arr_time TIMESTAMPTZ,
    dep_estimated TIMESTAMPTZ,
    arr_estimated TIMESTAMPTZ,
    status TEXT,
    delayed_min INTEGER,
    ingested_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (flight_iata, dep_time)
);

CREATE TABLE IF NOT EXISTS flight_positions (
    icao24 TEXT NOT NULL,
    callsign TEXT,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    altitude DOUBLE PRECISION,
    velocity DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (recorded_at);

-- Create a default partition for today and tomorrow
CREATE TABLE IF NOT EXISTS flight_positions_default PARTITION OF flight_positions DEFAULT;

CREATE TABLE IF NOT EXISTS flight_events (
    id BIGSERIAL PRIMARY KEY,
    flight_iata TEXT,
    icao24 TEXT,
    event_type TEXT,
    payload JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS raw_api_responses (
    id BIGSERIAL PRIMARY KEY,
    source TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT now()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_flight_schedules_iata ON flight_schedules (flight_iata);
CREATE INDEX IF NOT EXISTS idx_flight_events_flight ON flight_events (flight_iata);
CREATE INDEX IF NOT EXISTS idx_flight_events_icao ON flight_events (icao24);
CREATE INDEX IF NOT EXISTS idx_raw_api_source ON raw_api_responses (source, recorded_at);
