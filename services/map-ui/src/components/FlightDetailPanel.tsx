import React, { useEffect, useState } from 'react';
import type { EnrichedFlight } from '../types';

interface Props {
  icao24: string;
  onClose: () => void;
}

export function FlightDetailPanel({ icao24, onClose }: Props) {
  const [flight, setFlight] = useState<EnrichedFlight | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`/api/flights/${icao24}`)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => setFlight(data))
      .catch(err => setError(err.message));
  }, [icao24]);

  return (
    <div style={{
      position: 'absolute', top: 24, right: 24, width: 320, maxHeight: '80vh',
      background: 'rgba(0,0,0,0.85)', borderRadius: 8, padding: 16, color: '#fff',
      fontSize: 13, fontFamily: 'system-ui, sans-serif', zIndex: 2, overflowY: 'auto',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <span style={{ fontWeight: 700, fontSize: 16 }}>{icao24.toUpperCase()}</span>
        <button onClick={onClose} style={{
          background: 'none', border: 'none', color: '#aaa', cursor: 'pointer', fontSize: 18,
        }}>x</button>
      </div>

      {error && <div style={{ color: '#f88' }}>Error: {error}</div>}

      {flight && (
        <div>
          <Row label="Type" value={flight.type} />
          <Row label="Callsign" value={flight.callsign || flight.position?.callsign || '—'} />
          {flight.schedule && (
            <>
              <Row label="Flight" value={flight.schedule.flightIata} />
              <Row label="Route" value={`${flight.schedule.depIata} → ${flight.schedule.arrIata}`} />
              <Row label="Status" value={flight.schedule.status || '—'} />
            </>
          )}
          {flight.delay && (
            <Row label="Delay" value={`${flight.delay.delayedMin} min`} />
          )}
          <Row label="Altitude" value={`${Math.round(flight.position.altitude)} m`} />
          <Row label="Velocity" value={`${Math.round(flight.position.velocity)} m/s`} />
          <Row label="Heading" value={`${Math.round(flight.position.heading)}°`} />
          <Row label="On ground" value={flight.position.onGround ? 'Yes' : 'No'} />
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0', borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
      <span style={{ color: '#999' }}>{label}</span>
      <span>{value}</span>
    </div>
  );
}
