import React from 'react'

const s = {
  bar: {
    display: 'flex', alignItems: 'center', gap: 0,
    background: 'rgba(22,27,34,0.85)', borderBottom: '1px solid var(--border)',
    height: 56, position: 'relative', zIndex: 100, flexShrink: 0,
    backdropFilter: 'blur(8px)',
  },
  logoWrap: {
    padding: '0 20px', borderRight: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', justifyContent: 'center',
    height: '100%', flexShrink: 0, gap: 2,
  },
  logo: {
    fontFamily: 'var(--sans)', fontSize: 16, fontWeight: 800,
    color: 'var(--blue-bright)', letterSpacing: 2,
  },
  logoSub: {
    fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)',
    letterSpacing: 1.8, fontWeight: 400,
  },
  kpiStrip: { display: 'flex', flex: 1, height: '100%' },
  tabStrip: {
    display: 'flex',
    height: '100%',
    alignItems: 'stretch',
    gap: 0,
    flex: '0 0 auto',
  },
  tab: {
    fontFamily: 'var(--mono)',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    padding: '0 18px',
    display: 'flex',
    alignItems: 'center',
    cursor: 'pointer',
    borderBottom: '2px solid transparent',
    color: 'var(--muted)',
    transition: 'all 0.15s ease',
    whiteSpace: 'nowrap',
    userSelect: 'none',
    background: 'transparent',
    border: 'none',
    outline: 'none',
  },
  kpi: {
    display: 'flex', flexDirection: 'column', justifyContent: 'center',
    padding: '0 18px', borderRight: '1px solid var(--border)', height: '100%',
    minWidth: 100,
  },
  kpiVal: { fontFamily: 'var(--mono)', fontSize: 22, fontWeight: 500, lineHeight: 1, letterSpacing: -0.3 },
  kpiLabel: { fontFamily: 'var(--sans)', fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1.4, marginTop: 4, fontWeight: 600 },
  timeBlock: {
    display: 'flex', flexDirection: 'column', justifyContent: 'center',
    padding: '0 14px', borderRight: '1px solid var(--border)', height: '100%', gap: 1,
    minWidth: 170,
  },
  timeLine: { display: 'flex', alignItems: 'baseline', gap: 4, lineHeight: 1 },
  timeVal: { fontFamily: 'var(--mono)', fontSize: 13, fontWeight: 500, color: 'var(--amber)' },
  timeLabel: { fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', letterSpacing: 1 },
  controls: {
    display: 'flex', alignItems: 'center', gap: 9,
    padding: '0 16px', height: '100%', flexShrink: 0,
  },
  btnStart: (running) => ({
    background: running ? 'rgba(240,75,75,0.12)' : 'rgba(61,139,255,0.12)',
    color: running ? 'var(--red)' : 'var(--blue-bright)',
    border: `1px solid ${running ? 'rgba(240,75,75,0.4)' : 'rgba(61,139,255,0.4)'}`,
    fontFamily: 'var(--sans)', fontSize: 13, fontWeight: 700, letterSpacing: 0.8,
    padding: '6px 14px', borderRadius: 5, cursor: 'pointer', textTransform: 'uppercase',
    whiteSpace: 'nowrap',
  }),
  btnReset: {
    background: 'transparent', border: '1px solid var(--border)',
    color: 'var(--muted)', fontFamily: 'var(--sans)', fontSize: 13,
    fontWeight: 600, padding: '6px 10px', borderRadius: 5, cursor: 'pointer',
    textTransform: 'uppercase', letterSpacing: 0.6,
  },
}

function fmtElapsed(sec) {
  if (!sec) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

function fmtClock(sec) {
  if (!sec) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const hh = String(h).padStart(2, '0')
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

export default function TopBar({
  currentDay, totalDays, elapsedSeconds, realElapsedSeconds,
  fechaSimulada,
  simRateLabel,
  kpis,
  isRunning,
  running, onToggleSim, onReset,
  useBackend, backendState,
  theme, onToggleTheme,
  onNavigate,
  onIniciar,
  screen,
  hasSimulation,
}) {
  const isBackendRunning = Boolean(useBackend && backendState?.enEjecucion)
  const isBackendFinished = Boolean(useBackend && backendState?.finalizada)
  const effectiveRunning = isRunning !== undefined ? isRunning : running
  const primaryActionLabel = isBackendRunning
    ? (effectiveRunning ? '⏸ PAUSAR' : '▶ REANUDAR')
    : isBackendFinished
      ? '↺ REINICIAR'
      : 'CONFIGURAR'

  const kpiCards = [
    { label: 'En tránsito',    value: hasSimulation ? kpis.bagsInTransit.toLocaleString() : '—', color: hasSimulation ? 'var(--text-bright)' : 'var(--muted)' },
    { label: 'Cumpl. SLA',     value: hasSimulation ? `${Number(kpis.slaCompliance).toFixed(1)}%` : '—', color: hasSimulation ? (kpis.slaCompliance >= 90 ? 'var(--green)' : kpis.slaCompliance >= 75 ? 'var(--amber)' : 'var(--red)') : 'var(--muted)' },
    { label: 'Vuelos activos', value: hasSimulation ? String(kpis.activeFlights) : '—', color: hasSimulation ? 'var(--blue-bright)' : 'var(--muted)' },
    { label: 'SLA vencidos',   value: hasSimulation ? String(kpis.slaViolated) : '—', color: hasSimulation && kpis.slaViolated > 0 ? 'var(--red)' : 'var(--muted)' },
  ]

  const tabs = [
    { key: 'main', label: 'OPERACIONES' },
    { key: 'envios', label: 'ENVÍOS' },
    { key: 'dashboard', label: 'DASHBOARD' },
    { key: 'resultados', label: 'RESULTADOS' },
  ]

  function isActiveTab(key) {
    if (key === 'main') return screen === 'main'
    if (key === 'resultados') return screen === 'resultados' || screen === 'config'
    return screen === key
  }

  return (
    <div style={s.bar}>
      <div style={s.logoWrap}>
        <div style={s.logo}>TASF<span style={{ color: 'var(--muted)' }}>.</span>B2B</div>
        <div style={s.logoSub}>OPS DASHBOARD</div>
      </div>

      <div style={s.kpiStrip}>
        {kpiCards.map((k) => (
          <div key={k.label} style={s.kpi}>
            <span style={{ ...s.kpiVal, color: k.color }}>{k.value}</span>
            <span style={s.kpiLabel}>{k.label}</span>
          </div>
        ))}
      </div>

      <div style={s.tabStrip}>
        {tabs.map((tab) => {
          const active = isActiveTab(tab.key)
          const disabled = !hasSimulation && (tab.key === 'envios' || tab.key === 'dashboard')
          return (
            <button
              key={tab.key}
              disabled={disabled}
              style={{
                ...s.tab,
                color: active ? 'var(--text-bright)' : 'var(--muted)',
                borderBottom: active ? '2px solid var(--blue)' : '2px solid transparent',
                opacity: disabled ? 0.3 : 1,
                cursor: disabled ? 'default' : 'pointer',
              }}
              onClick={() => !disabled && onNavigate(tab.key)}
              onMouseEnter={(event) => {
                if (disabled) return
                event.currentTarget.style.color = active ? 'var(--text-bright)' : 'var(--text)'
                event.currentTarget.style.background = 'rgba(255,255,255,0.03)'
              }}
              onMouseLeave={(event) => {
                if (disabled) return
                event.currentTarget.style.color = active ? 'var(--text-bright)' : 'var(--muted)'
                event.currentTarget.style.background = 'transparent'
              }}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      {/* Simulation time indicators */}
      <div style={s.timeBlock}>
        <div style={s.timeLine}>
          <span style={{ ...s.timeVal, fontSize: 14 }}>{currentDay > 0 ? `DÍA ${currentDay} / ${totalDays}` : '—'}</span>
        </div>
        <div style={s.timeLine}>
          <span style={{ ...s.timeVal, fontSize: 11, color: 'var(--muted)' }}>
            {fechaSimulada ? fechaSimulada : fmtElapsed(elapsedSeconds)}
          </span>
          <span style={s.timeLabel}>sim</span>
        </div>
        <div style={s.timeLine}>
          <span style={{ ...s.timeVal, fontSize: 11, color: 'var(--text)' }}>{fmtClock(realElapsedSeconds)}</span>
          <span style={s.timeLabel}>real</span>
        </div>
      </div>

      <div style={s.controls}>
        <button style={s.btnReset} onClick={onToggleTheme}>{theme === 'dark' ? '☀' : '🌙'}</button>
        <button style={s.btnStart(isBackendRunning || effectiveRunning)} onClick={() => {
          if (isBackendRunning) {
            onToggleSim()
            return
          }
          if (isBackendFinished) {
            onReset()
            return
          }
          if (onIniciar) {
            onIniciar()
            return
          }
          onToggleSim()
        }}>
          {primaryActionLabel}
        </button>
        <button style={s.btnReset} onClick={onReset}>Reset</button>
      </div>
    </div>
  )
}
