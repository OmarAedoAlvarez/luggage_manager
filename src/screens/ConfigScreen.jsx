import React, { useState } from 'react'
import { startSimulation } from '../services/api.js'

const PERIOD_OPTIONS = [
  { key: '3', label: '3 DIAS', sublabel: 'Simulacion corta' },
  { key: '5', label: '5 DIAS', sublabel: 'Simulacion estandar' },
  { key: '7', label: '7 DIAS', sublabel: 'Simulacion semanal' },
]

const ALGORITHM_OPTIONS = [
  { key: 'SIMULATED_ANNEALING', label: 'SA', sublabel: 'Simulated Annealing' },
  { key: 'TABU_SEARCH', label: 'TS', sublabel: 'Tabu Search' },
]

function sectionHeaderStyle() {
  return {
    fontFamily: 'var(--mono)',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 2,
    color: 'var(--muted)',
    marginBottom: 12,
    display: 'block',
  }
}

export default function ConfigScreen({ onCancel, onSimulationStarted }) {
  const [periodo, setPeriodo] = useState('3')
  const [algoritmo, setAlgoritmo] = useState('SIMULATED_ANNEALING')
  const [fechaInicio, setFechaInicio] = useState('2026-01-02')
  const [escalaMinima, setEscalaMinima] = useState(10)
  const [tiempoRecogida, setTiempoRecogida] = useState(10)
  const [semaforo, setSemaforo] = useState({ verde: 60, ambar: 85 })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const semaforoError = Number(semaforo.ambar) <= Number(semaforo.verde)
    ? 'Umbral ámbar debe ser mayor que verde'
    : null

  function rowStyle(selected) {
    return {
      width: '100%',
      padding: '10px 12px',
      border: `1px solid ${selected ? 'var(--blue)' : 'var(--border)'}`,
      background: selected ? 'rgba(88,166,255,0.06)' : 'transparent',
      marginBottom: 4,
      cursor: loading ? 'not-allowed' : 'pointer',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      textAlign: 'left',
      opacity: loading ? 0.7 : 1,
    }
  }

  async function handleSimular() {
    if (semaforoError) {
      setError(semaforoError)
      return
    }
    const dias = Number.parseInt(periodo, 10)
    const params = {
      algoritmo,
      dias,
      esColapso: false,
      minutosEscalaMinima: Number(escalaMinima),
      minutosRecogidaDestino: Number(tiempoRecogida),
      umbralSemaforoVerde: Number(semaforo.verde),
      umbralSemaforoAmbar: Number(semaforo.ambar),
      fechaInicio,
    }

    setLoading(true)
    setError(null)
    try {
      const state = await startSimulation(params)
      onSimulationStarted(state, params)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      {loading && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 2000, background: 'rgba(13,17,23,0.88)', backdropFilter: 'blur(4px)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 24 }}>
          <div style={{ width: 52, height: 52, borderRadius: '50%', border: '3px solid rgba(88,166,255,0.15)', borderTopColor: 'var(--blue)', animation: 'spin 0.75s linear infinite' }} />
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)', letterSpacing: 1, marginBottom: 6 }}>Calculando rutas óptimas…</div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)' }}>
              {algoritmo === 'SIMULATED_ANNEALING' ? 'Simulated Annealing' : 'Tabu Search'} · {periodo} días
            </div>
          </div>
        </div>
      )}
      <div style={{ height: '100%', display: 'grid', gridTemplateColumns: '420px 1fr', background: 'var(--bg)' }}>
        <aside style={{ borderRight: '1px solid var(--border)', overflowY: 'auto', padding: '24px 20px' }}>
          <span style={sectionHeaderStyle()}>Tipo de periodo</span>
          {PERIOD_OPTIONS.map((option) => {
            const selected = periodo === option.key
            return (
              <button key={option.key} style={rowStyle(selected)} onClick={() => !loading && setPeriodo(option.key)} disabled={loading}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)' }}>{option.label}</span>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--muted)' }}>{option.sublabel}</span>
                </div>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: selected ? 'var(--blue)' : 'transparent', border: `1px solid ${selected ? 'var(--blue)' : 'var(--border)'}` }} />
              </button>
            )
          })}

          <div style={{ marginTop: 20 }}>
            <span style={sectionHeaderStyle()}>Algoritmo</span>
            <div style={{ display: 'flex', border: '1px solid var(--border)' }}>
              {ALGORITHM_OPTIONS.map((option, idx) => {
                const selected = algoritmo === option.key
                return (
                  <button
                    key={option.key}
                    onClick={() => !loading && setAlgoritmo(option.key)}
                    disabled={loading}
                    style={{
                      flex: 1,
                      padding: '10px 12px',
                      border: 'none',
                      borderRight: idx === 0 ? '1px solid var(--border)' : 'none',
                      background: selected ? 'rgba(88,166,255,0.1)' : 'transparent',
                      cursor: loading ? 'not-allowed' : 'pointer',
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: 2,
                    }}
                  >
                    <span style={{ fontFamily: 'var(--mono)', fontSize: 14, fontWeight: 700, color: selected ? 'var(--blue)' : 'var(--muted)' }}>{option.label}</span>
                    <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: selected ? 'var(--blue)' : 'var(--muted)', opacity: 0.7 }}>{option.sublabel}</span>
                  </button>
                )
              })}
            </div>
          </div>

          <div style={{ marginTop: 20 }}>
            <span style={sectionHeaderStyle()}>Fecha de inicio</span>
            <input
              type="date"
              value={fechaInicio}
              onChange={(event) => setFechaInicio(event.target.value)}
              disabled={loading}
              style={{ width: '100%', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 13, padding: '8px 10px' }}
            />
          </div>
        </aside>

        <section style={{ overflowY: 'auto', padding: '24px 20px', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <div style={{ marginBottom: 20 }}>
            <span style={sectionHeaderStyle()}>Parámetros de conexión</span>
            {[
              { key: 'escala', label: 'Escala mínima (min)', value: escalaMinima, setter: setEscalaMinima },
              { key: 'recogida', label: 'Tiempo recogida destino (min)', value: tiempoRecogida, setter: setTiempoRecogida },
            ].map(({ key, label, value, setter }) => (
              <div key={key} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <span style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>{label}</span>
                <input
                  type="number"
                  min={1}
                  max={60}
                  value={value}
                  disabled={loading}
                  onChange={(event) => {
                    const v = Number(event.target.value)
                    if (Number.isFinite(v) && v >= 1 && v <= 60) setter(v)
                  }}
                  style={{ width: 64, textAlign: 'right', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 13, padding: '6px 8px', appearance: 'textfield', MozAppearance: 'textfield', WebkitAppearance: 'none' }}
                />
              </div>
            ))}
          </div>

          <div style={{ marginBottom: 20 }}>
            <span style={sectionHeaderStyle()}>Rangos de semáforo</span>
            {[
              { key: 'verde', color: 'var(--green)', label: 'Verde', description: 'Ocupación normal' },
              { key: 'ambar', color: 'var(--amber)', label: 'Ámbar', description: 'Ocupación elevada' },
            ].map((item) => (
              <div key={item.key} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto auto', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: item.color }} />
                <div>
                  <div style={{ color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 12 }}>{item.label}</div>
                  <div style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>{item.description}</div>
                </div>
                <span style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>{'<'}</span>
                <input
                  type="number"
                  value={semaforo[item.key]}
                  disabled={loading}
                  onChange={(event) => {
                    const value = Number(event.target.value)
                    setSemaforo((prev) => ({ ...prev, [item.key]: Number.isFinite(value) ? value : prev[item.key] }))
                  }}
                  style={{ width: 56, textAlign: 'right', background: 'rgba(255,255,255,0.04)', border: `1px solid ${semaforoError && item.key === 'ambar' ? 'var(--red)' : 'var(--border)'}`, color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 12, padding: '4px 6px', appearance: 'textfield', MozAppearance: 'textfield', WebkitAppearance: 'none' }}
                />
              </div>
            ))}
            {semaforoError && (
              <div style={{ color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 11, marginTop: 4 }}>{semaforoError}</div>
            )}
          </div>

          <div style={{ marginBottom: 20 }}>
            <span style={sectionHeaderStyle()}>Resumen de configuración</span>
            {[
              ['Algoritmo', algoritmo === 'SIMULATED_ANNEALING' ? 'Simulated Annealing' : 'Tabu Search'],
              ['Periodo', `${periodo} días desde ${fechaInicio}`],
              ['Escala mínima', `${escalaMinima} min`],
              ['Tiempo recogida', `${tiempoRecogida} min`],
              ['Semáforo verde', `< ${semaforo.verde}%`],
              ['Semáforo ámbar', `< ${semaforo.ambar}%`],
            ].map(([label, value]) => (
              <div key={label} style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <span style={{ color: 'var(--muted)', fontSize: 12 }}>{label}</span>
                <span style={{ color: 'var(--blue)', fontFamily: 'var(--mono)', fontSize: 13 }}>{value}</span>
              </div>
            ))}
          </div>

          <div style={{ marginTop: 'auto', position: 'sticky', bottom: 0, background: 'var(--bg)', borderTop: '1px solid var(--border)', padding: '14px 20px', display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
            <div style={{ flex: 1 }}>
              {error ? (
                <div style={{ borderLeft: '2px solid var(--red)', background: 'rgba(248,81,73,0.06)', padding: '8px 12px', color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 12 }}>
                  {error}
                </div>
              ) : null}
            </div>
            <button
              onClick={onCancel}
              disabled={loading}
              style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 13, textTransform: 'uppercase', letterSpacing: 1, padding: '8px 16px', cursor: loading ? 'not-allowed' : 'pointer' }}
            >
              Cancelar
            </button>
            <button
              onClick={handleSimular}
              disabled={Boolean(semaforoError) || loading}
              style={{
                background: 'rgba(88,166,255,0.12)',
                border: '1px solid rgba(88,166,255,0.4)',
                color: 'var(--blue)',
                fontFamily: 'var(--mono)',
                fontSize: 13,
                textTransform: 'uppercase',
                letterSpacing: 1,
                fontWeight: 700,
                padding: '8px 20px',
                cursor: Boolean(semaforoError) || loading ? 'not-allowed' : 'pointer',
                opacity: Boolean(semaforoError) || loading ? 0.35 : 1,
              }}
            >
              {loading ? 'PROCESANDO...' : '▶ SIMULAR'}
            </button>
          </div>
        </section>
      </div>
    </>
  )
}
