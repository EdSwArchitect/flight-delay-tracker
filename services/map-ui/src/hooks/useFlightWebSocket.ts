import { useCallback, useEffect, useRef, useState } from 'react';
import type { EnrichedFlight, WsMessage } from '../types';

export function useFlightWebSocket() {
  const [flights, setFlights] = useState<Map<string, EnrichedFlight>>(new Map());
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout>>();

  const connect = useCallback(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/positions`;
    const ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      setConnected(true);
      console.log('WebSocket connected');
    };

    ws.onmessage = (event) => {
      try {
        const msg: WsMessage = JSON.parse(event.data);
        if (msg.type === 'snapshot' && msg.flights) {
          const map = new Map<string, EnrichedFlight>();
          for (const f of msg.flights) {
            map.set(f.icao24, f);
          }
          setFlights(map);
        } else if (msg.type === 'delta' && msg.flights) {
          setFlights(prev => {
            const next = new Map(prev);
            for (const f of msg.flights!) {
              next.set(f.icao24, f);
            }
            return next;
          });
        }
      } catch (e) {
        console.error('Failed to parse WS message', e);
      }
    };

    ws.onclose = () => {
      setConnected(false);
      console.log('WebSocket disconnected, reconnecting in 3s...');
      reconnectTimer.current = setTimeout(connect, 3000);
    };

    ws.onerror = (err) => {
      console.error('WebSocket error', err);
      ws.close();
    };

    wsRef.current = ws;
  }, []);

  useEffect(() => {
    connect();
    return () => {
      clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connect]);

  return { flights: Array.from(flights.values()), connected };
}
