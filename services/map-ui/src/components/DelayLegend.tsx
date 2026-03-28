import React from 'react';

const LEGEND_ITEMS = [
  { label: 'Unknown', color: 'rgb(136,135,128)' },
  { label: 'On time', color: 'rgb(57,158,117)' },
  { label: '1-30 min', color: 'rgb(239,159,39)' },
  { label: '31-60 min', color: 'rgb(216,90,48)' },
  { label: '>60 min', color: 'rgb(226,75,74)' },
];

export function DelayLegend() {
  return (
    <div style={{
      position: 'absolute', bottom: 24, left: 24, background: 'rgba(0,0,0,0.75)',
      borderRadius: 8, padding: '12px 16px', color: '#fff', fontSize: 13,
      fontFamily: 'system-ui, sans-serif', zIndex: 1,
    }}>
      <div style={{ fontWeight: 600, marginBottom: 8 }}>Delay</div>
      {LEGEND_ITEMS.map(item => (
        <div key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{
            display: 'inline-block', width: 12, height: 12, borderRadius: '50%',
            background: item.color,
          }} />
          <span>{item.label}</span>
        </div>
      ))}
    </div>
  );
}
