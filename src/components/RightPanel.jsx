
import React from 'react'

const s = {
  panel: {
    background: 'var(--panel)', borderLeft: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflow: 'hidden',
  },
  section: { display: 'flex', flexDirection: 'column', overflow: 'hidden' },
  sectionPad: { padding: '14px 14px', borderBottom: '1px solid var(--border)', flexShrink: 0 },
  title: {
    fontFamily: 'var(--sans)', fontSize: 10, textTransform: 'uppercase',
    letterSpacing: 2, color: 'var(--muted)', marginBottom: 10,
    display: 'block', fontWeight: 700,
  },
  scrollable: { overflowY: 'auto', flex: 1 },
  flightItem: (selected) => ({
    padding: '9px 14px', borderBottom: '1px solid rgba(99,152,255,0.07)',
    display: 'flex', alignItems: 'center', gap: 9, cursor: 'pointer',
    background: selected ? 'rgba(61,139,255,0.09)' : 'transparent',
    borderLeft: `2px solid ${selected ? '#3d8bff' : 'transparent'}`,
    transition: 'background 0.15s ease',
    userSelect: 'none',
  }),
  dot: (color) => ({
    width: 7, height: 7, borderRadius: '50%', flexShrink: 0,
    background: color, boxShadow: `0 0 6px ${color}`,
    animation: 'pulse-dot 2.2s ease-in-out infinite',
  }),
  flightRoute: {
    fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text-bright)',
    lineHeight: 1.3, fontWeight: 500,
  },
  flightMeta: {
    fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', marginTop: 2,
  },
  badge: (color) => ({
    fontFamily: 'var(--mono)', fontSize: 10, padding: '2px 7px',
    borderRadius: 4, background: `${color}18`, color,
    border: `1px solid ${color}40`, marginLeft: 'auto', flexShrink: 0,
    textTransform: 'uppercase', letterSpacing: 0.4, fontWeight: 600,
  }),
  airportItem: { marginBottom: 10 },
  airportHeader: {
    display: 'flex', justifyContent: 'space-between',
    alignItems: 'center', marginBottom: 5,
  },
  airportName: { fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--text)' },
  capBar: { height: 3, background: 'rgba(255,255,255,0.07)', borderRadius: 3, overflow: 'hidden' },
  capFill: (pct, color) => ({
    height: '100%', width: `${Math.min(100, pct)}%`,
    background: color, borderRadius: 3,
    transition: 'width 0.4s ease',
  }),
}

function warehouseColor(ap, threshold) {
  const occ = ap.currentOccupation ?? ap.ocupacionActual ?? 0
  const cap = ap.warehouseCapacity ?? ap.capacidadAlmacen ?? 600
  const pct = cap > 0 ? (occ / cap) * 100 : 0
  if (pct >= threshold)      return '#f04b4b'
  if (pct >= threshold - 20) return '#f5a623'
  return '#22d07a'
}

export default function RightPanel({ flights, airports, threshold, selectedFlight, setSelectedFlight, onVueloClick }) {
  const flightList = flights || []
  const airportList = airports || []
  const activeFlights = flightList.filter((f) => f.status === 'active')
  const sortedAirports = [...airportList]
    .sort((a, b) => {
      const aOcc = a.currentOccupation ?? a.ocupacionActual ?? 0
      const aCap = a.warehouseCapacity ?? a.capacidadAlmacen ?? 600
      const bOcc = b.currentOccupation ?? b.ocupacionActual ?? 0
      const bCap = b.warehouseCapacity ?? b.capacidadAlmacen ?? 600
      return (bOcc / bCap) - (aOcc / aCap)
    })
  const occupiedAirports = sortedAirports.filter((ap) => (ap.currentOccupation ?? ap.ocupacionActual ?? 0) > 0)
  const hiddenCount = sortedAirports.length - occupiedAirports.length

  return (
    <div style={s.panel}>

      {/* ── ACTIVE FLIGHTS ────────────────────────────────────────────── */}
      <div style={{ ...s.sectionPad, flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <span style={s.title}>Vuelos activos</span>
        <div style={s.scrollable}>
          {activeFlights.map((f) => {
            const isSelected = selectedFlight === f.id
            const loadPct    = Math.round((f.currentLoad / f.capacity) * 100)
            const color      = loadPct >= 90 ? '#f04b4b' : loadPct >= 70 ? '#f5a623' : '#22d07a'
            return (
              <div key={f.id} style={s.flightItem(isSelected)}
                onClick={() => { setSelectedFlight(isSelected ? null : f.id); if (onVueloClick) onVueloClick(f) }}>
                <div style={s.dot(color)} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={s.flightRoute}>{f.origin} → {f.destination}</div>
                  <div style={s.flightMeta}>{f.currentLoad}/{f.capacity} · {f.type === 'continental' ? 'CONT' : 'INT'}</div>
                </div>
                <div style={s.badge(color)}>{loadPct}%</div>
              </div>
            )
          })}
          {activeFlights.length === 0 && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)', padding: '12px 0' }}>
              Sin vuelos activos
            </div>
          )}
        </div>
      </div>

      {/* ── WAREHOUSE PER AIRPORT ─────────────────────────────────────── */}
      <div style={{ ...s.sectionPad, flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <span style={s.title}>Warehouse por aeropuerto</span>
        <div style={{ overflowY: 'auto', flex: 1, paddingBottom: 8 }}>
        {occupiedAirports.map((ap) => {
          const color = warehouseColor(ap, threshold)
          const occ   = ap.currentOccupation ?? ap.ocupacionActual ?? 0
          const cap   = ap.warehouseCapacity ?? ap.capacidadAlmacen ?? 600
          const pct   = Math.round(cap > 0 ? (occ / cap) * 100 : 0)
          return (
            <div key={ap.id} style={s.airportItem}>
              <div style={s.airportHeader}>
                <span style={s.airportName}>{ap.id} — {ap.name}</span>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color }}>{pct}%</span>
              </div>
              <div style={s.capBar}>
                <div style={s.capFill(pct, color)} />
              </div>
            </div>
          )
        })}
        {hiddenCount > 0 && (
          <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)', padding: '8px 0 4px', textAlign: 'center', opacity: 0.6 }}>
            {hiddenCount} aeropuerto{hiddenCount !== 1 ? 's' : ''} sin actividad
          </div>
        )}
        </div>
      </div>

    </div>
  )
}
