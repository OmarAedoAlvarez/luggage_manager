import React, { useEffect } from 'react'

const s = {
  overlay: {
    position: 'fixed', top: 56, left: 0, right: 0, bottom: 0, zIndex: 500,
    display: 'flex', justifyContent: 'flex-end', pointerEvents: 'auto',
  },
  backdrop: {
    flex: 1, height: '100%', border: 'none',
    background: 'rgba(0,0,0,0.5)', cursor: 'pointer',
  },
  panel: {
    position: 'relative', width: 380, height: '100%',
    background: 'var(--panel)', borderLeft: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflowY: 'auto', zIndex: 501,
  },
  header: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '14px 16px', borderBottom: '1px solid var(--border)',
    flexShrink: 0,
  },
  iata: {
    fontFamily: 'var(--mono)', fontSize: 20, fontWeight: 700,
    color: 'var(--text-bright)', letterSpacing: 1,
  },
  headerName: {
    fontFamily: 'var(--sans)', fontSize: 11, color: 'var(--muted)',
    flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  pill: (color) => ({
    fontFamily: 'var(--mono)', fontSize: 8, fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: 0.8,
    padding: '3px 8px', borderRadius: 4,
    background: `${color}1f`, color, border: `1px solid ${color}66`,
    flexShrink: 0,
  }),
  closeBtn: {
    background: 'transparent', border: 'none',
    color: 'var(--muted)', cursor: 'pointer',
    fontFamily: 'var(--mono)', fontSize: 14, lineHeight: 1,
    padding: '2px 4px', flexShrink: 0,
  },
  section: { padding: '14px 16px', borderBottom: '1px solid var(--border)' },
  sectionTitle: {
    fontFamily: 'var(--sans)', fontSize: 8, textTransform: 'uppercase',
    letterSpacing: 2, color: 'var(--muted)', fontWeight: 700,
    marginBottom: 10, display: 'block',
  },
  barTrack: {
    height: 4, background: 'rgba(255,255,255,0.07)',
    overflow: 'hidden', marginBottom: 6,
  },
  barFill: (pct, color) => ({
    height: '100%', width: `${Math.min(100, pct)}%`,
    background: color, transition: 'width 0.4s ease',
  }),
  barLabel: {
    display: 'flex', justifyContent: 'space-between',
    fontFamily: 'var(--mono)', fontSize: 10,
  },
  row: {
    display: 'flex', justifyContent: 'space-between',
    alignItems: 'baseline', marginBottom: 7, gap: 8,
  },
  rowLabel: { fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)', flexShrink: 0 },
  rowVal: { fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-bright)', textAlign: 'right' },
  flightItem: {
    display: 'flex', alignItems: 'center', gap: 8,
    padding: '7px 0', borderBottom: '1px solid rgba(99,152,255,0.07)',
  },
  dot: (color) => ({
    width: 6, height: 6, borderRadius: '50%', flexShrink: 0,
    background: color, boxShadow: `0 0 5px ${color}`,
  }),
  flightCode: { fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-bright)', flex: 1 },
  flightMeta: { fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)' },
  stat: {
    flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center',
    padding: '10px 0',
  },
  statVal: { fontFamily: 'var(--mono)', fontSize: 16, fontWeight: 600, color: 'var(--text-bright)' },
  statLabel: { fontFamily: 'var(--mono)', fontSize: 8, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1, marginTop: 3 },
}

function semaforoColor(pct) {
  if (pct >= 85) return 'var(--red)'
  if (pct >= 60) return 'var(--amber)'
  return 'var(--green)'
}

function semaforoLabel(pct) {
  if (pct >= 85) return 'CRÍTICO'
  if (pct >= 60) return 'ALTO'
  return 'NORMAL'
}

function flightColor(load, cap) {
  const p = cap > 0 ? (load / cap) * 100 : 0
  if (p >= 90) return 'var(--red)'
  if (p >= 70) return 'var(--amber)'
  return 'var(--green)'
}

export default function DrawerAeropuerto({ airport, vuelos, onClose }) {
  useEffect(() => {
    if (!airport) return
    function onKey(e) { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [airport, onClose])

  if (!airport) return null

  const occ = airport.currentOccupation ?? airport.ocupacionActual ?? 0
  const cap = airport.warehouseCapacity ?? airport.capacidadAlmacen ?? 600
  const pct = cap > 0 ? Math.round((occ / cap) * 100) : 0
  const color = semaforoColor(pct)

  const connectedFlights = (vuelos || [])
    .filter((v) => {
      const origin = v.origin || v.origen
      const dest = v.destination || v.destino
      return origin === airport.id || dest === airport.id
    })
    .slice(0, 8)

  return (
    <div style={s.overlay}>
      <button aria-label="Cerrar" style={s.backdrop} onClick={onClose} />
      <aside style={s.panel}>

        {/* Header */}
        <div style={s.header}>
          <span style={s.iata}>{airport.id}</span>
          <span style={s.headerName}>{airport.name || airport.nombre}</span>
          <span style={s.pill(color)}>{semaforoLabel(pct)}</span>
          <button style={s.closeBtn} onClick={onClose} aria-label="Cerrar">✕</button>
        </div>

        {/* Occupancy */}
        <div style={s.section}>
          <span style={s.sectionTitle}>Ocupación de almacén</span>
          <div style={s.barTrack}>
            <div style={s.barFill(pct, color)} />
          </div>
          <div style={s.barLabel}>
            <span style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 9 }}>
              {occ.toLocaleString()} / {cap.toLocaleString()} maletas
            </span>
            <span style={{ color, fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700 }}>{pct}%</span>
          </div>
        </div>

        {/* Info General */}
        <div style={s.section}>
          <span style={s.sectionTitle}>Info general</span>
          <div style={s.row}>
            <span style={s.rowLabel}>Nombre</span>
            <span style={s.rowVal}>{airport.name || airport.nombre || '—'}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Ciudad</span>
            <span style={s.rowVal}>{airport.ciudad || '—'}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Continente</span>
            <span style={s.rowVal}>{airport.continent || airport.continente || '—'}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Coords</span>
            <span style={s.rowVal}>
              {airport.lat != null ? `${airport.lat.toFixed(3)}, ${airport.lng.toFixed(3)}` : '—'}
            </span>
          </div>
        </div>

        {/* Connected Flights */}
        <div style={{ ...s.section, flex: 1 }}>
          <span style={s.sectionTitle}>
            Vuelos conectados {connectedFlights.length > 0 ? `(${connectedFlights.length})` : ''}
          </span>
          {connectedFlights.length === 0 ? (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)' }}>Sin vuelos activos</span>
          ) : connectedFlights.map((v, i) => {
            const code = v.id || v.codigoVuelo || `FL-${i}`
            const origin = v.origin || v.origen || '?'
            const dest = v.destination || v.destino || '?'
            const load = v.currentLoad ?? v.cargaActual ?? 0
            const vcap = v.capacity ?? v.capacidadTotal ?? 300
            const c = flightColor(load, vcap)
            return (
              <div key={code} style={s.flightItem}>
                <div style={s.dot(c)} />
                <span style={s.flightCode}>{code}</span>
                <span style={s.flightMeta}>{origin} → {dest}</span>
                <span style={{ ...s.flightMeta, color: c }}>{Math.round((load / vcap) * 100)}%</span>
              </div>
            )
          })}
        </div>

        {/* Stats */}
        <div style={{ ...s.section, display: 'flex', borderBottom: 'none' }}>
          <div style={s.stat}>
            <span style={s.statVal}>{occ.toLocaleString()}</span>
            <span style={s.statLabel}>Maletas</span>
          </div>
          <div style={{ width: 1, background: 'var(--border)', margin: '8px 0' }} />
          <div style={s.stat}>
            <span style={s.statVal}>{cap.toLocaleString()}</span>
            <span style={s.statLabel}>Capacidad</span>
          </div>
          <div style={{ width: 1, background: 'var(--border)', margin: '8px 0' }} />
          <div style={s.stat}>
            <span style={{ ...s.statVal, color }}>{pct}%</span>
            <span style={s.statLabel}>Ocupación</span>
          </div>
        </div>

      </aside>
    </div>
  )
}
