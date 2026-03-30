import React, { useState } from 'react';
import Map from 'react-map-gl/maplibre';
import { DeckGL } from '@deck.gl/react';
import { ScatterplotLayer } from '@deck.gl/layers';
import { useFlightWebSocket } from './hooks/useFlightWebSocket';
import { getDelayColor } from './utils/delayColor';
import { DelayLegend } from './components/DelayLegend';
import { FlightPopup } from './components/FlightDetailPanel';
import type { EnrichedFlight } from './types';
import 'maplibre-gl/dist/maplibre-gl.css';

const MAPTILER_KEY = import.meta.env.VITE_MAPTILER_KEY || '';
const MAP_STYLE = MAPTILER_KEY
  ? `https://api.maptiler.com/maps/dataviz-dark/style.json?key=${MAPTILER_KEY}`
  : `https://demotiles.maplibre.org/style.json`;

const INITIAL_VIEW = {
  longitude: -73.78,
  latitude: 40.64,
  zoom: 7,
  pitch: 0,
  bearing: 0,
};

interface PickedFlight {
  flight: EnrichedFlight;
  x: number;
  y: number;
}

export default function App() {
  const { flights, connected } = useFlightWebSocket();
  const [picked, setPicked] = useState<PickedFlight | null>(null);

  const layer = new ScatterplotLayer<EnrichedFlight>({
    id: 'flights',
    data: flights,
    getPosition: (d) => [d.position.longitude, d.position.latitude, d.position.altitude],
    getRadius: 4000,
    getFillColor: (d) => {
      const delayMin = d.delay?.delayedMin ?? d.schedule?.delayedMin ?? null;
      return [...getDelayColor(delayMin), 200] as [number, number, number, number];
    },
    pickable: true,
    radiusMinPixels: 3,
    radiusMaxPixels: 8,
    onClick: (info) => {
      if (info.object) {
        setPicked({ flight: info.object, x: info.x, y: info.y });
      }
    },
  });

  return (
    <div style={{ width: '100%', height: '100%' }}>
      <DeckGL
        initialViewState={INITIAL_VIEW}
        controller={true}
        layers={[layer]}
        onClick={(info) => {
          if (!info.object) setPicked(null);
        }}
      >
        <Map mapStyle={MAP_STYLE} />
      </DeckGL>

      {/* Connection status */}
      <div style={{
        position: 'absolute', top: 12, left: 12, padding: '6px 12px',
        background: connected ? 'rgba(57,158,117,0.8)' : 'rgba(226,75,74,0.8)',
        color: '#fff', borderRadius: 4, fontSize: 12, fontFamily: 'system-ui, sans-serif',
        zIndex: 1,
      }}>
        {connected ? `Live — ${flights.length} flights` : 'Disconnected'}
      </div>

      <DelayLegend />

      {picked && (
        <FlightPopup
          flight={picked.flight}
          x={picked.x}
          y={picked.y}
          onClose={() => setPicked(null)}
        />
      )}
    </div>
  );
}
