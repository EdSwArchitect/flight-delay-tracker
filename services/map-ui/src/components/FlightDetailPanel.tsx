import React from 'react';
import type { EnrichedFlight } from '../types';
import { getDelayColor } from '../utils/delayColor';

interface Props {
  flight: EnrichedFlight;
  x: number;
  y: number;
  onClose: () => void;
}

const POPUP_WIDTH = 280;
const POPUP_OFFSET = 12;

export function FlightPopup({ flight, x, y, onClose }: Props) {
  const delayMin = flight.delay?.delayedMin ?? flight.schedule?.delayedMin ?? null;
  const [r, g, b] = getDelayColor(delayMin);
  const callsign = flight.callsign || flight.position?.callsign || null;
  const flightIata = flight.schedule?.flightIata;

  // Position popup so it doesn't overflow the viewport
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const left = x + POPUP_WIDTH + POPUP_OFFSET > vw
    ? x - POPUP_WIDTH - POPUP_OFFSET
    : x + POPUP_OFFSET;
  const top = Math.min(y, vh - 340);

  return (
    <div
      style={{
        position: 'absolute',
        left,
        top,
        width: POPUP_WIDTH,
        background: 'rgba(15,15,20,0.92)',
        borderRadius: 8,
        padding: 14,
        color: '#fff',
        fontSize: 13,
        fontFamily: 'system-ui, sans-serif',
        zIndex: 10,
        boxShadow: '0 4px 24px rgba(0,0,0,0.5)',
        border: `1px solid rgba(${r},${g},${b},0.5)`,
        pointerEvents: 'auto',
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div>
          <span style={{ fontWeight: 700, fontSize: 15 }}>
            {flightIata || callsign || flight.icao24.toUpperCase()}
          </span>
          {flightIata && callsign && (
            <span style={{ color: '#888', marginLeft: 8, fontSize: 12 }}>{callsign}</span>
          )}
        </div>
        <button
          onClick={onClose}
          style={{
            background: 'none', border: 'none', color: '#888', cursor: 'pointer',
            fontSize: 16, lineHeight: 1, padding: '0 2px',
          }}
        >
          x
        </button>
      </div>

      {/* Route */}
      {flight.schedule && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10,
          fontSize: 14, fontWeight: 600,
        }}>
          <span>{flight.schedule.depIata}</span>
          <span style={{ color: '#555', flex: 1, textAlign: 'center' }}>---</span>
          <span>{flight.schedule.arrIata}</span>
        </div>
      )}

      {/* Delay badge */}
      {delayMin !== null && (
        <div style={{
          display: 'inline-block', padding: '3px 8px', borderRadius: 4, marginBottom: 10,
          background: `rgba(${r},${g},${b},0.2)`, color: `rgb(${r},${g},${b})`,
          fontSize: 12, fontWeight: 600,
        }}>
          {delayMin <= 0 ? 'On time' : `Delayed ${delayMin} min`}
        </div>
      )}

      {/* Details grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 12px', marginTop: 4 }}>
        {flight.schedule?.status && (
          <Field label="Status" value={flight.schedule.status} />
        )}
        <Field label="ICAO24" value={flight.icao24.toUpperCase()} />
        <Field label="Altitude" value={formatAlt(flight.position.altitude, flight.position.onGround)} />
        <Field label="Speed" value={`${Math.round(flight.position.velocity * 1.944)} kts`} />
        <Field label="Heading" value={`${Math.round(flight.position.heading)}\u00B0`} />
        {flight.schedule?.depTime && (
          <Field label="Departure" value={formatTime(flight.schedule.depTime)} />
        )}
        {flight.schedule?.arrTime && (
          <Field label="Arrival" value={formatTime(flight.schedule.arrTime)} />
        )}
        {flight.schedule?.depEstimated && flight.schedule.depEstimated !== flight.schedule.depTime && (
          <Field label="Est. depart" value={formatTime(flight.schedule.depEstimated)} />
        )}
        {flight.schedule?.arrEstimated && flight.schedule.arrEstimated !== flight.schedule.arrTime && (
          <Field label="Est. arrive" value={formatTime(flight.schedule.arrEstimated)} />
        )}
      </div>

      {/* Type tag */}
      <div style={{ marginTop: 10, fontSize: 11, color: '#555' }}>
        {flight.type === 'resolved' ? 'Resolved' :
         flight.type === 'position_only' ? 'Position only' :
         `Unresolvable: ${flight.reason || 'unknown'}`}
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ color: '#777', fontSize: 11 }}>{label}</div>
      <div style={{ fontWeight: 500 }}>{value}</div>
    </div>
  );
}

function formatAlt(meters: number, onGround: boolean): string {
  if (onGround) return 'On ground';
  return `${Math.round(meters * 3.281).toLocaleString()} ft`;
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return iso;
  }
}
