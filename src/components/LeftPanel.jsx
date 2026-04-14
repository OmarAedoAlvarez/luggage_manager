import React from 'react'

const s = {
  panel: {
    background: 'var(--panel)', borderRight: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflowY: 'auto', overflowX: 'hidden',
  },
  section: { padding: '16px 14px', borderBottom: '1px solid var(--border)' },
  title: {
    fontFamily: 'var(--sans)', fontSize: 10, textTransform: 'uppercase',
    letterSpacing: 2.2, color: 'var(--muted)', marginBottom: 12,
    display: 'block', fontWeight: 700,
  },
  chipRow: { display: 'flex', flexDirection: 'column', gap: 6 },
  chip: (active, color) => ({
    fontFamily: 'var(--sans)', fontSize: 12, padding: '7px 11px', borderRadius: 5,
    border: `1px solid ${active ? color + '60' : 'var(--border)'}`,
    color: active ? color : 'var(--muted)',
    background: active ? color + '14' : 'rgba(255,255,255,0.02)',
    cursor: 'pointer', fontWeight: 600, letterSpacing: 0.3,
    display: 'flex', alignItems: 'center', gap: 8,
    userSelect: 'none', transition: 'all 0.15s ease',
  }),
  dot: (color) => ({
    width: 7, height: 7, borderRadius: '50%', background: color,
    flexShrink: 0, boxShadow: `0 0 6px ${color}80`,
  }),
  sliderWrap: { display: 'flex', flexDirection: 'column', gap: 8 },
  sliderRow: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  sliderKey: { fontFamily: 'var(--sans)', fontSize: 12, color: 'var(--text)', fontWeight: 500 },
  sliderVal: { fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--amber)', fontWeight: 500 },
  slider: { width: '100%', accentColor: 'var(--amber)', cursor: 'pointer', height: 4, margin: '4px 0' },
  legendRow: { display: 'flex', flexDirection: 'column', gap: 9 },
  legendItem: { display: 'flex', alignItems: 'center', gap: 9, fontSize: 12, color: 'var(--muted)', fontFamily: 'var(--mono)' },
  legendDot: (color) => ({
    width: 9, height: 9, borderRadius: '50%', flexShrink: 0,
    background: color, boxShadow: `0 0 5px ${color}90`,
  }),
  legendDash: (color) => ({
    width: 20, height: 0, flexShrink: 0,
    borderBottom: `2px dashed ${color}`,
  }),
}

const STATUS_CHIPS = [
  { val: 'green', label: 'Entregado a tiempo', color: '#3d8bff' },
  { val: 'amber', label: 'Cancelado / riesgo', color: '#3d8bff' },
  { val: 'red',   label: 'SLA vencido',        color: '#3d8bff' },
]

const ROUTE_CHIPS = [
  { val: 'same',  label: 'Continental',       color: '#3d8bff' },
  { val: 'inter', label: 'Intercontinental',  color: '#3d8bff' },
]

export default function LeftPanel({ filters, setFilters, threshold, setThreshold }) {
  const statusFilters = filters?.status || []
  const routeFilters = filters?.route || []

  const toggleFilter = (group, val) =>
    setFilters((prev) => ({
      ...prev,
      [group]: (prev[group] || []).includes(val)
        ? (prev[group] || []).filter((v) => v !== val)
        : [...(prev[group] || []), val],
    }))

  return (
    <div style={s.panel}>

      {/* DELIVERY STATUS */}
      <div style={s.section}>
        <span style={s.title}>Estado de entrega</span>
        <div style={s.chipRow}>
          {STATUS_CHIPS.map(({ val, label, color }) => {
            const active = statusFilters.includes(val)
            return (
              <div key={val} style={s.chip(active, color)} onClick={() => toggleFilter('status', val)}>
                <div style={s.dot(active ? color : 'var(--muted)')} />
                {label}
              </div>
            )
          })}
        </div>
      </div>

      {/* ROUTE TYPE */}
      <div style={s.section}>
        <span style={s.title}>Tipo de ruta</span>
        <div style={s.chipRow}>
          {ROUTE_CHIPS.map(({ val, label, color }) => {
            const active = routeFilters.includes(val)
            return (
              <div key={val} style={s.chip(active, color)} onClick={() => toggleFilter('route', val)}>
                <div style={{ ...s.dot(active ? color : 'var(--muted)'), borderRadius: 1 }} />
                {label}
              </div>
            )
          })}
        </div>
      </div>

      {/* WAREHOUSE THRESHOLD */}
      <div style={s.section}>
        <span style={s.title}>Alerta Warehouse</span>
        <div style={s.sliderWrap}>
          <div style={s.sliderRow}>
            <span style={s.sliderKey}>Umbral crítico</span>
            <span style={s.sliderVal}>{threshold}%</span>
          </div>
          <input
            type="range" min={50} max={99} step={1}
            value={threshold} style={s.slider}
            onChange={(e) => setThreshold(+e.target.value)}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontFamily: 'var(--mono)', fontSize: 8, color: 'var(--muted)' }}>50%</span>
            <span style={{ fontFamily: 'var(--mono)', fontSize: 8, color: 'var(--muted)' }}>99%</span>
          </div>
        </div>
      </div>

      {/* SEMAPHORE LEGEND */}
      <div style={{ ...s.section, borderBottom: 'none', marginTop: 'auto' }}>
        <span style={s.title}>Semáforo</span>
        <div style={s.legendRow}>
          {[
            { color: '#22d07a', label: 'OK / dentro de SLA',     type: 'dot'  },
            { color: '#f5a623', label: 'Alerta / warehouse alto', type: 'dot'  },
            { color: '#f04b4b', label: 'Crítico / SLA vencido',   type: 'dot'  },
            { color: '#f5a623', label: 'Ruta replanificada',      type: 'dash' },
          ].map(({ color, label, type }) => (
            <div key={label} style={s.legendItem}>
              {type === 'dot'
                ? <div style={s.legendDot(color)} />
                : <div style={s.legendDash(color)} />
              }
              <span>{label}</span>
            </div>
          ))}
        </div>
      </div>

    </div>
  )
}
