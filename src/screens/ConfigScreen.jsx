import React, { useMemo, useRef, useState } from 'react'
import { api } from '../services/api.js'

const PERIOD_OPTIONS = [
  { key: '3', label: '3 DIAS', sublabel: 'Simulacion corta' },
  { key: '5', label: '5 DIAS', sublabel: 'Simulacion estandar' },
  { key: '7', label: '7 DIAS', sublabel: 'Simulacion semanal' },
  { key: 'COLAPSO', label: 'COLAPSO', sublabel: 'Hasta limite operativo' },
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

function todayIsoDate() {
  return '2026-01-02'
}

function extractOrigin(filename) {
  const match = filename.match(/_envios_([A-Za-z]{4})_?.*\.txt$/i)
  return match ? match[1].toUpperCase() : null
}

export default function ConfigScreen({ onCancel, onSimulationStarted }) {
  const [periodo, setPeriodo] = useState('5')
  const [fechaInicio, setFechaInicio] = useState(todayIsoDate())
  const [horaInicio, setHoraInicio] = useState('06:00')
  const [semaforo, setSemaforo] = useState({ verde: 60, ambar: 85, rojo: 85 })
  const [files, setFiles] = useState([])
  const [dragActive, setDragActive] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const fileInputRef = useRef(null)

  const origins = useMemo(() => {
    const set = new Set(files.map((file) => extractOrigin(file.name)).filter(Boolean))
    return [...set]
  }, [files])

  const daysLabel = periodo === 'COLAPSO' ? 'COLAPSO' : `${periodo}`

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

  function appendFiles(nextFiles) {
    if (!nextFiles?.length) return
    setFiles((prev) => {
      const map = new Map(prev.map((file) => [`${file.name}-${file.size}-${file.lastModified}`, file]))
      nextFiles.forEach((file) => map.set(`${file.name}-${file.size}-${file.lastModified}`, file))
      return [...map.values()]
    })
  }

  function removeFile(index) {
    setFiles((prev) => prev.filter((_, i) => i !== index))
  }

  function onFileInputChange(event) {
    appendFiles(Array.from(event.target.files || []))
  }

  function onDrop(event) {
    event.preventDefault()
    setDragActive(false)
    if (loading) return
    appendFiles(Array.from(event.dataTransfer.files || []).filter((file) => file.name.toLowerCase().endsWith('.txt')))
  }

  async function handleSimular() {
    const esColapso = periodo === 'COLAPSO'
    const dias = esColapso ? 0 : Number.parseInt(periodo, 10)
    const params = {
      algoritmo: 'SIMULATED_ANNEALING',
      dias,
      esColapso,
      capacidadAlmacen: 800,
      capacidadVuelo: 300,
      minutosEscalaMinima: 10,
      minutosRecogidaDestino: 10,
      umbralSemaforoVerde: Number(semaforo.verde),
      umbralSemaforoAmbar: Number(semaforo.ambar),
      fechaInicio,
      horaInicio,
    }

    console.info('[ConfigScreen] startSimulation clicked', {
      fechaInicio,
      horaInicio,
      periodo,
      esColapso,
      dias,
      fileCount: files.length,
      fileNames: files.map((file) => file.name),
      params,
    })

    setLoading(true)
    setError(null)
    try {
      const state = await api.startSimulation(params, files)
      console.info('[ConfigScreen] startSimulation succeeded', {
        hasState: Boolean(state),
        stateKeys: state ? Object.keys(state) : [],
      })
      onSimulationStarted(state, params, files)
    } catch (err) {
      console.error('[ConfigScreen] startSimulation failed', err)
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
            <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)' }}>Simulated Annealing · {files.length} archivo{files.length !== 1 ? 's' : ''}</div>
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
          <span style={sectionHeaderStyle()}>Fecha de inicio</span>
          <input
            type="date"
            value={fechaInicio}
            onChange={(event) => setFechaInicio(event.target.value)}
            disabled={loading}
            style={{ width: '100%', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 13, padding: '8px 10px' }}
          />
          <input
            type="time"
            value={horaInicio}
            onChange={(event) => setHoraInicio(event.target.value)}
            disabled={loading}
            style={{ marginTop: 6, width: '100%', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 13, padding: '8px 10px' }}
          />
        </div>

        <div style={{ marginTop: 20 }}>
          <span style={sectionHeaderStyle()}>Rangos de semaforo</span>
          {[
            { key: 'verde', color: 'var(--green)', label: 'Verde', description: 'Ocupacion normal', sign: '<' },
            { key: 'ambar', color: 'var(--amber)', label: 'Ambar', description: 'Ocupacion elevada', sign: '<' },
            { key: 'rojo', color: 'var(--red)', label: 'Rojo', description: 'Ocupacion critica', sign: '>' },
          ].map((item) => (
            <div key={item.key} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto auto', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: '50%', background: item.color }} />
              <div>
                <div style={{ color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 12 }}>{item.label}</div>
                <div style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>{item.description}</div>
              </div>
              <span style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>{item.sign}</span>
              <input
                type="number"
                value={semaforo[item.key]}
                disabled={loading}
                onChange={(event) => {
                  const value = Number(event.target.value)
                  setSemaforo((prev) => ({ ...prev, [item.key]: Number.isFinite(value) ? value : prev[item.key] }))
                }}
                style={{ width: 56, textAlign: 'right', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 12, padding: '4px 6px', appearance: 'textfield', MozAppearance: 'textfield', WebkitAppearance: 'none' }}
              />
            </div>
          ))}
        </div>


      </aside>

      <section style={{ overflowY: 'auto', padding: '24px 20px', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <span style={sectionHeaderStyle()}>Carga de datos operativos</span>
        <p style={{ color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12, marginBottom: 16, lineHeight: 1.5 }}>
          Cargue uno o mas archivos _envios_XXXX.txt
          <br />
          del dataset del profesor. El origen se detecta
          <br />
          automaticamente desde el nombre del archivo.
        </p>

        <div
          role="button"
          tabIndex={0}
          onClick={() => !loading && fileInputRef.current?.click()}
          onDragOver={(event) => {
            event.preventDefault()
            if (!loading) setDragActive(true)
          }}
          onDragLeave={() => setDragActive(false)}
          onDrop={onDrop}
          style={{
            border: `1px dashed ${dragActive ? 'var(--blue)' : 'rgba(255,255,255,0.15)'}`,
            background: dragActive ? 'rgba(88,166,255,0.04)' : 'rgba(255,255,255,0.02)',
            padding: '32px 20px',
            textAlign: 'center',
            cursor: loading ? 'not-allowed' : 'pointer',
            marginBottom: 12,
            opacity: loading ? 0.6 : 1,
          }}
        >
          <div style={{ fontSize: 24, color: 'var(--muted)', marginBottom: 8 }}>↑</div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--muted)' }}>Arrastre archivos aqui</div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--muted)' }}>o haga clic para seleccionar</div>
        </div>

        <input
          ref={fileInputRef}
          type="file"
          accept=".txt"
          multiple
          style={{ display: 'none' }}
          onChange={onFileInputChange}
          disabled={loading}
        />

        <div>
          {files.map((file, index) => (
            <div key={`${file.name}-${index}`} style={{ padding: '6px 10px', border: '1px solid var(--border)', marginBottom: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--green)' }} />
                <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis' }}>{file.name}</span>
              </div>
              <button
                onClick={() => !loading && removeFile(index)}
                disabled={loading}
                style={{ border: 'none', background: 'transparent', color: 'var(--muted)', cursor: loading ? 'not-allowed' : 'pointer', fontSize: 14, lineHeight: 1 }}
              >
                ×
              </button>
            </div>
          ))}
        </div>

        {files.length > 0 ? (
          <div style={{ marginTop: 20 }}>
            <span style={sectionHeaderStyle()}>Resumen de datos</span>
            {[
              ['Archivos cargados', files.length],
              ['Aeropuertos origen', origins.length],
              ['Vuelos disponibles', '2,866'],
              ['Periodo', `${daysLabel} dias desde ${fechaInicio}`],
            ].map(([label, value]) => (
              <div key={label} style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <span style={{ color: 'var(--muted)', fontSize: 12 }}>{label}</span>
                <span style={{ color: 'var(--blue)', fontFamily: 'var(--mono)', fontSize: 13 }}>{value}</span>
              </div>
            ))}
          </div>
        ) : null}

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
            disabled={files.length === 0 || loading}
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
              cursor: files.length === 0 || loading ? 'not-allowed' : 'pointer',
              opacity: files.length === 0 || loading ? 0.35 : 1,
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
