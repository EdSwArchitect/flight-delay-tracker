export interface FlightPosition {
  icao24: string;
  callsign: string;
  longitude: number;
  latitude: number;
  altitude: number;
  onGround: boolean;
  velocity: number;
  heading: number;
  recordedAt: string;
}

export interface FlightSchedule {
  flightIata: string;
  depIata: string;
  arrIata: string;
  depTime: string;
  arrTime: string | null;
  depEstimated: string | null;
  arrEstimated: string | null;
  status: string | null;
  delayedMin: number | null;
}

export interface FlightDelay {
  flightIata: string;
  delayedMin: number;
  recordedAt: string;
}

export interface EnrichedFlight {
  type: 'resolved' | 'position_only' | 'unresolvable';
  icao24: string;
  position: FlightPosition;
  schedule?: FlightSchedule;
  delay?: FlightDelay | null;
  callsign?: string;
  reason?: string;
  resolvedAt: string;
}

export interface WsMessage {
  type: 'snapshot' | 'delta' | 'heartbeat';
  timestamp: string;
  flights?: EnrichedFlight[];
}
