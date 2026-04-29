import React, { useMemo, useState } from 'react'
import { api } from '../services/api'

export default function VuelosScreen({ simState }) {
  const [searchTerm, setSearchTerm] = useState('')
  const [cancelling, setCancelling] = useState(null)

  const flights = useMemo(() => {
    const list = simState?.vuelos || []
    if (!searchTerm) return list
    const term = searchTerm.toLowerCase()
    return list.filter(f => 
      f.codigoVuelo?.toLowerCase().includes(term) ||
      f.origen?.toLowerCase().includes(term) ||
      f.destino?.toLowerCase().includes(term)
    )
  }, [simState?.vuelos, searchTerm])

  const stats = useMemo(() => {
    const list = simState?.vuelos || []
    return {
      total: list.length,
      activos: list.filter(f => f.estado === 'activo' && !f.cancelado).length,
      cancelados: list.filter(f => f.cancelado).length,
    }
  }, [simState?.vuelos])

  async function handleCancel(codigoVuelo) {
    if (!window.confirm(`¿Estás seguro de cancelar el vuelo ${codigoVuelo}? Esto disparará una replanificación inmediata.`)) {
      return
    }
    setCancelling(codigoVuelo)
    try {
      await api.cancelFlight(codigoVuelo)
    } catch (err) {
      alert('Error al cancelar el vuelo: ' + err.message)
    } finally {
      setCancelling(null)
    }
  }

  return (
    <div style={{ padding: 24, height: '100%', display: 'flex', flexDirection: 'column', gap: 24 }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 24, color: 'var(--text-bright)', letterSpacing: -0.5 }}>Gestión de Vuelos</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--muted)', fontSize: 14 }}>Control interactivo de incidencias y cancelaciones</p>
        </div>
        <div style={{ display: 'flex', gap: 16 }}>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--blue)' }}>{stats.activos}</div>
            <div style={{ fontSize: 10, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1 }}>Activos</div>
          </div>
          <div style={{ width: 1, background: 'var(--border)', margin: '4px 0' }} />
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--red)' }}>{stats.cancelados}</div>
            <div style={{ fontSize: 10, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1 }}>Cancelados</div>
          </div>
        </div>
      </header>

      <div style={{ display: 'flex', gap: 12 }}>
        <input
          type="text"
          placeholder="Buscar por código, origen o destino..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          style={{
            flex: 1,
            background: 'var(--panel)',
            border: '1px solid var(--border)',
            padding: '10px 16px',
            borderRadius: 8,
            color: 'var(--text)',
            fontFamily: 'inherit',
            outline: 'none',
          }}
        />
      </div>

      <div style={{ flex: 1, overflowY: 'auto', background: 'var(--panel)', border: '1px solid var(--border)', borderRadius: 12 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead style={{ position: 'sticky', top: 0, background: 'var(--panel)', zIndex: 1, borderBottom: '1px solid var(--border)' }}>
            <tr style={{ color: 'var(--muted)', fontSize: 12, textTransform: 'uppercase', letterSpacing: 1 }}>
              <th style={{ padding: '16px 24px' }}>Código</th>
              <th style={{ padding: '16px 24px' }}>Ruta</th>
              <th style={{ padding: '16px 24px' }}>Salida / Llegada</th>
              <th style={{ padding: '16px 24px' }}>Carga</th>
              <th style={{ padding: '16px 24px' }}>Estado</th>
              <th style={{ padding: '16px 24px', textAlign: 'right' }}>Acción</th>
            </tr>
          </thead>
          <tbody>
            {flights.map((v) => {
              const isCancelado = v.cancelado || v.estado === 'cancelado'
              const loadPct = v.capacidadTotal > 0 ? (v.cargaActual / v.capacidadTotal) * 100 : 0
              const loadColor = loadPct > 90 ? 'var(--red)' : loadPct > 70 ? 'var(--amber)' : 'var(--green)'

              return (
                <tr key={v.codigoVuelo} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)', color: isCancelado ? 'var(--muted)' : 'inherit' }}>
                  <td style={{ padding: '16px 24px', fontFamily: 'var(--mono)', color: isCancelado ? 'inherit' : 'var(--blue)' }}>{v.codigoVuelo}</td>
                  <td style={{ padding: '16px 24px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span>{v.origen}</span>
                      <span style={{ opacity: 0.3 }}>→</span>
                      <span>{v.destino}</span>
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--muted)', marginTop: 4 }}>{v.tipo}</div>
                  </td>
                  <td style={{ padding: '16px 24px', fontFamily: 'var(--mono)', fontSize: 13 }}>
                    {v.horaSalida} - {v.horaLlegada}
                  </td>
                  <td style={{ padding: '16px 24px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <div style={{ flex: 1, width: 60, height: 4, background: 'rgba(255,255,255,0.05)', borderRadius: 2, overflow: 'hidden' }}>
                        <div style={{ width: `${Math.min(100, loadPct)}%`, height: '100%', background: isCancelado ? 'var(--muted)' : loadColor }} />
                      </div>
                      <span style={{ fontSize: 12, fontFamily: 'var(--mono)' }}>{v.cargaActual}/{v.capacidadTotal}</span>
                    </div>
                  </td>
                  <td style={{ padding: '16px 24px' }}>
                    <span style={{
                      padding: '4px 8px',
                      borderRadius: 4,
                      fontSize: 10,
                      fontWeight: 700,
                      background: isCancelado ? 'rgba(240,75,75,0.1)' : 'rgba(34,208,122,0.1)',
                      color: isCancelado ? 'var(--red)' : 'var(--green)',
                      border: `1px solid ${isCancelado ? 'rgba(240,75,75,0.2)' : 'rgba(34,208,122,0.2)'}`,
                    }}>
                      {isCancelado ? 'CANCELADO' : 'OPERATIVO'}
                    </span>
                  </td>
                  <td style={{ padding: '16px 24px', textAlign: 'right' }}>
                    {!isCancelado && (
                      <button
                        onClick={() => handleCancel(v.codigoVuelo)}
                        disabled={cancelling === v.codigoVuelo}
                        style={{
                          background: 'rgba(240,75,75,0.1)',
                          border: '1px solid rgba(240,75,75,0.3)',
                          color: 'var(--red)',
                          padding: '6px 12px',
                          borderRadius: 6,
                          fontSize: 12,
                          fontWeight: 600,
                          cursor: 'pointer',
                          transition: 'all 0.2s',
                        }}
                        onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--red)'; e.currentTarget.style.color = 'white' }}
                        onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(240,75,75,0.1)'; e.currentTarget.style.color = 'var(--red)' }}
                      >
                        {cancelling === v.codigoVuelo ? '...' : 'Cancelar'}
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
            {flights.length === 0 && (
              <tr>
                <td colSpan="6" style={{ padding: 48, textAlign: 'center', color: 'var(--muted)' }}>
                  No se encontraron vuelos con esos criterios.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
