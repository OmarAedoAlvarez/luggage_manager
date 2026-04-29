import React, { useEffect, useState } from 'react'
import { api } from '../services/api.js'
import Modal from '../components/Modal.jsx'

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
    position: 'relative', width: 400, height: '100%',
    background: 'var(--panel)', borderLeft: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflowY: 'auto', zIndex: 501,
  },
  header: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '14px 16px', borderBottom: '1px solid var(--border)',
    flexShrink: 0,
  },
  envioId: {
    fontFamily: 'var(--mono)', fontSize: 13, fontWeight: 700,
    color: 'var(--text-bright)', letterSpacing: 0.5, flex: 1,
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
  row: {
    display: 'flex', justifyContent: 'space-between',
    alignItems: 'baseline', marginBottom: 7, gap: 8,
  },
  rowLabel: { fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)', flexShrink: 0 },
  rowVal: { fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-bright)', textAlign: 'right' },
  // Timeline
  tlRow: { display: 'flex', alignItems: 'stretch', gap: 12, position: 'relative' },
  tlDotCol: { display: 'flex', flexDirection: 'column', alignItems: 'center', width: 14, flexShrink: 0 },
  tlDot: (color) => ({
    width: 10, height: 10, borderRadius: '50%', flexShrink: 0,
    background: color, boxShadow: `0 0 6px ${color}`, zIndex: 1,
  }),
  tlLine: { flex: 1, width: 1, background: 'var(--border)', margin: '2px 0' },
  tlContent: { paddingBottom: 14, flex: 1, minWidth: 0 },
  tlCode: { fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-bright)', fontWeight: 600 },
  tlMeta: { fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)', marginTop: 2 },
  // Status
  statusMsg: { fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)', padding: '20px 16px' },
  // Tiempo restante big number
  tiempoBlock: {
    padding: '16px 16px', borderBottom: 'none', textAlign: 'center',
  },
}

function estadoColor(estado) {
  if (!estado) return 'var(--muted)'
  const e = estado.toUpperCase()
  if (e === 'ENTREGADO') return 'var(--green)'
  if (e === 'RETRASADO') return 'var(--red)'
  return 'var(--amber)'
}

function escalaDotColor(escala) {
  if (!escala) return 'var(--muted)'
  const h = (escala.horaLlegadaEst || '').toLowerCase()
  if (h === 'completado' || h === 'entregado') return 'var(--green)'
  if (h === 'retrasado') return 'var(--red)'
  return 'var(--amber)'
}

export default function DrawerEnvio({ envioId, onClose }) {
  const [envio, setEnvio]   = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError]   = useState(null)
  const [confirmCancelOpen, setConfirmCancelOpen] = useState(false)

  useEffect(() => {
    if (!envioId) {
      setEnvio(null)
      return
    }
    let active = true
    setLoading(true)
    setError(null)
    setEnvio(null)

    api.getEnvioById(envioId)
      .then((v) => { if (active) setEnvio(v) })
      .catch((err) => { if (active) setError(err instanceof Error ? err.message : String(err)) })
      .finally(() => { if (active) setLoading(false) })

    return () => { active = false }
  }, [envioId])

  useEffect(() => {
    if (!envioId) return
    function onKey(e) { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [envioId, onClose])

  if (!envioId) return null

  const eColor = estadoColor(envio?.estado)
  const escalas = envio?.planDetalle?.escalas || []

  return (
    <div style={s.overlay}>
      <button aria-label="Cerrar" style={s.backdrop} onClick={onClose} />
      <aside style={s.panel}>

        {/* Header */}
        <div style={s.header}>
          <span style={s.envioId}>{envio?.idEnvio || envioId}</span>
          {envio?.codigoAerolinea && (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--muted)' }}>
              {envio.codigoAerolinea}
            </span>
          )}
          {envio?.estado && <span style={s.pill(eColor)}>{envio.estado}</span>}
          <button style={s.closeBtn} onClick={onClose} aria-label="Cerrar">✕</button>
        </div>

        {/* Loading / Error */}
        {loading && <div style={s.statusMsg}>Cargando envío...</div>}
        {error && <div style={{ ...s.statusMsg, color: 'var(--red)' }}>{error}</div>}

        {envio && (
          <>
            {/* Info Envío */}
            <div style={s.section}>
              <span style={s.sectionTitle}>Información del envío</span>
              <div style={s.row}>
                <span style={s.rowLabel}>Origen</span>
                <span style={s.rowVal}>{envio.aeropuertoOrigen || '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>Destino</span>
                <span style={s.rowVal}>{envio.aeropuertoDestino || '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>Maletas</span>
                <span style={s.rowVal}>{envio.cantidadMaletas ?? '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>SLA</span>
                <span style={s.rowVal}>{envio.sla != null ? `${envio.sla} día${envio.sla === 1 ? '' : 's'}` : '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>Fecha límite</span>
                <span style={s.rowVal}>{envio.fechaLimiteSla ? String(envio.fechaLimiteSla).substring(0, 10) : '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>Plan resumen</span>
                <span style={{ ...s.rowVal, fontSize: 9, wordBreak: 'break-all' }}>{envio.planResumen || '—'}</span>
              </div>
              <div style={s.row}>
                <span style={s.rowLabel}>Ubicación actual</span>
                <span style={s.rowVal}>{envio.ubicacionActual || '—'}</span>
              </div>
            </div>

            {/* Ruta Asignada — timeline */}
            {escalas.length > 0 && (
              <div style={{ ...s.section, flex: 1 }}>
                <span style={s.sectionTitle}>Ruta asignada</span>
                <div>
                  {escalas.map((escala, idx) => {
                    const dotColor = escalaDotColor(escala)
                    const isLast = idx === escalas.length - 1
                    return (
                      <div key={`${escala.codigoVuelo}-${idx}`} style={s.tlRow}>
                        <div style={s.tlDotCol}>
                          <div style={s.tlDot(dotColor)} />
                          {!isLast && <div style={s.tlLine} />}
                        </div>
                        <div style={s.tlContent}>
                          <div style={s.tlCode}>
                            {escala.codigoVuelo || '—'} — {escala.codigoAeropuerto || '?'}
                          </div>
                          <div style={s.tlMeta}>
                            Salida {escala.horaSalidaEst || '—'} · Llegada {escala.horaLlegadaEst || '—'}
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {/* Tiempo Restante */}
            <div style={s.tiempoBlock}>
              <div style={{
                fontFamily: 'var(--mono)', fontSize: 28, fontWeight: 700,
                color: eColor, letterSpacing: -0.5,
              }}>
                {envio.tiempoRestante != null ? `${envio.tiempoRestante}h` : '—'}
              </div>
              <div style={{ fontFamily: 'var(--mono)', fontSize: 8, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1.5, marginTop: 4 }}>
                Tiempo restante
              </div>
            </div>

            {/* Acciones */}
            {(envio.estado !== 'CANCELADO' && envio.estado !== 'ENTREGADO' && envio.estado !== 'EN_TRANSITO') && (
              <div style={{ padding: '0 16px 20px', textAlign: 'center' }}>
                <button
                  disabled={loading}
                  onClick={() => setConfirmCancelOpen(true)}
                  style={{
                    width: '100%',
                    background: 'rgba(240,75,75,0.1)',
                    border: '1px solid rgba(240,75,75,0.3)',
                    color: 'var(--red)',
                    padding: '8px',
                    borderRadius: 4,
                    fontFamily: 'var(--mono)',
                    fontSize: 11,
                    fontWeight: 700,
                    cursor: 'pointer',
                    textTransform: 'uppercase',
                    letterSpacing: 1,
                  }}
                >
                  Cancelar Envío
                </button>
              </div>
            )}

            <Modal
              isOpen={confirmCancelOpen}
              title="Cancelar Envío"
              confirmLabel="Sí, cancelar"
              onClose={() => setConfirmCancelOpen(false)}
              onConfirm={async () => {
                setLoading(true)
                try {
                  await api.cancelEnvio(envio.idEnvio)
                  onClose()
                } catch (err) {
                  alert('Error al cancelar: ' + err.message)
                } finally {
                  setLoading(false)
                }
              }}
            >
              ¿Estás seguro de que deseas cancelar el envío <strong>{envio.idEnvio}</strong>? 
              Se liberará la capacidad y se replanificarán las maletas restantes.
            </Modal>
          </>
        )}

      </aside>
    </div>
  )
}
