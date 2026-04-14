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
  code: {
    fontFamily: 'var(--mono)', fontSize: 16, fontWeight: 700,
    color: 'var(--text-bright)', letterSpacing: 1,
  },
  route: {
    fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)',
    flex: 1,
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
  timeline: { display: 'flex', flexDirection: 'column', gap: 0 },
  tlRow: { display: 'flex', alignItems: 'stretch', gap: 12 },
  tlDotCol: { display: 'flex', flexDirection: 'column', alignItems: 'center', width: 14, flexShrink: 0 },
  tlDot: (color, pulse) => ({
    width: 10, height: 10, borderRadius: '50%', flexShrink: 0,
    background: color, boxShadow: `0 0 6px ${color}`,
    animation: pulse ? 'pulse-dot 2.2s ease-in-out infinite' : 'none',
  }),
  tlLine: {
    flex: 1, width: 1, background: 'var(--border)', margin: '2px 0',
  },
  tlContent: {
    paddingBottom: 16, flex: 1,
  },
  tlLabel: { fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-bright)', fontWeight: 600 },
  tlMeta: { fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)', marginTop: 2 },
}

function loadColor(pct) {
  if (pct >= 90) return 'var(--red)'
  if (pct >= 70) return 'var(--amber)'
  return 'var(--green)'
}

function estadoColor(estado) {
  if (!estado) return 'var(--muted)'
  const e = estado.toLowerCase()
  if (e === 'cancelado' || e === 'cancelled') return 'var(--red)'
  if (e === 'activo' || e === 'active') return 'var(--green)'
  return 'var(--amber)'
}

export default function DrawerVuelo({ vuelo, onClose }) {
  useEffect(() => {
    if (!vuelo) return
    function onKey(e) { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [vuelo, onClose])

  if (!vuelo) return null

  const code    = vuelo.id || vuelo.codigoVuelo || '—'
  const origin  = vuelo.origin  || vuelo.origen  || '?'
  const dest    = vuelo.destination || vuelo.destino || '?'
  const tipo    = vuelo.type || vuelo.tipo || '—'
  const estado  = vuelo.status || vuelo.estado || '—'
  const salida  = vuelo.horaSalida || '—'
  const llegada = vuelo.horaLlegada || '—'
  const load    = vuelo.currentLoad ?? vuelo.cargaActual ?? 0
  const cap     = vuelo.capacity ?? vuelo.capacidadTotal ?? 300
  const pct     = cap > 0 ? Math.round((load / cap) * 100) : 0
  const color   = loadColor(pct)
  const eColor  = estadoColor(estado)
  const isActivo = estado === 'active' || estado === 'activo'

  return (
    <div style={s.overlay}>
      <button aria-label="Cerrar" style={s.backdrop} onClick={onClose} />
      <aside style={s.panel}>

        {/* Header */}
        <div style={s.header}>
          <span style={s.code}>{code}</span>
          <span style={s.route}>{origin} → {dest}</span>
          <span style={s.pill(eColor)}>{estado.toUpperCase()}</span>
          <button style={s.closeBtn} onClick={onClose} aria-label="Cerrar">✕</button>
        </div>

        {/* Carga */}
        <div style={s.section}>
          <span style={s.sectionTitle}>Carga del vuelo</span>
          <div style={s.barTrack}>
            <div style={s.barFill(pct, color)} />
          </div>
          <div style={s.barLabel}>
            <span style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 9 }}>
              {load} / {cap} maletas
            </span>
            <span style={{ color, fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700 }}>{pct}%</span>
          </div>
        </div>

        {/* Info */}
        <div style={s.section}>
          <span style={s.sectionTitle}>Información</span>
          <div style={s.row}>
            <span style={s.rowLabel}>Tipo</span>
            <span style={s.rowVal}>{tipo === 'continental' ? 'Continental' : tipo === 'intercontinental' ? 'Intercontinental' : tipo}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Hora salida</span>
            <span style={s.rowVal}>{salida}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Hora llegada</span>
            <span style={s.rowVal}>{llegada}</span>
          </div>
          <div style={s.row}>
            <span style={s.rowLabel}>Capacidad</span>
            <span style={s.rowVal}>{cap} maletas</span>
          </div>
        </div>

        {/* Trayecto */}
        <div style={{ ...s.section, borderBottom: 'none', flex: 1 }}>
          <span style={s.sectionTitle}>Trayecto</span>
          <div style={s.timeline}>
            {/* Origen */}
            <div style={s.tlRow}>
              <div style={s.tlDotCol}>
                <div style={s.tlDot('var(--blue-bright)', false)} />
                <div style={s.tlLine} />
              </div>
              <div style={s.tlContent}>
                <div style={s.tlLabel}>{origin}</div>
                <div style={s.tlMeta}>Salida {salida}</div>
              </div>
            </div>
            {/* En vuelo */}
            <div style={s.tlRow}>
              <div style={s.tlDotCol}>
                <div style={s.tlDot(color, isActivo)} />
                <div style={s.tlLine} />
              </div>
              <div style={s.tlContent}>
                <div style={{ ...s.tlLabel, color: isActivo ? color : 'var(--muted)' }}>
                  {isActivo ? 'En vuelo' : 'En espera'}
                </div>
                <div style={s.tlMeta}>{load} / {cap} maletas · {pct}% carga</div>
              </div>
            </div>
            {/* Destino */}
            <div style={s.tlRow}>
              <div style={s.tlDotCol}>
                <div style={s.tlDot('var(--green)', false)} />
              </div>
              <div style={s.tlContent}>
                <div style={s.tlLabel}>{dest}</div>
                <div style={s.tlMeta}>Llegada {llegada}</div>
              </div>
            </div>
          </div>
        </div>

      </aside>
    </div>
  )
}
