import React, { useMemo } from 'react'

const getEnvios = (s) =>
  s?.envios || s?.routes?.map((r) => ({
    idEnvio: r.id,
    codigoAerolinea: r.baggageId,
    aeropuertoOrigen: r.flightLegs?.[0]?.origin,
    aeropuertoDestino: r.flightLegs?.slice(-1)[0]?.destination,
    cantidadMaletas: r.bags,
    estado: r.status === 'green'
      ? 'ENTREGADO'
      : r.status === 'red'
        ? 'RETRASADO'
        : 'EN_TRANSITO',
    sla: r.type === 'same' ? 1 : 2,
    planResumen: r.flightLegs?.map((l) => `${l.origin}->${l.destination}`).join(' | '),
    tiempoRestante: r.etaRemaining ? `${Math.round(r.etaRemaining)}h` : '—',
  })) || []

const getAeropuertos = (s) => s?.aeropuertos || s?.airports || []

const getKpis = (s) => s?.kpis || {
  maletasEnTransito: s?.kpis?.bagsInTransit || 0,
  maletasEntregadas: s?.kpis?.bagsDelivered || 0,
  cumplimientoSLA: s?.kpis?.slaCompliance || 0,
  vuelosActivos: s?.kpis?.activeFlights || 0,
  slaVencidos: s?.kpis?.slaViolated || 0,
  ocupacionPromedioAlmacen: 0,
}

const getLog = (s) => s?.logOperaciones || []

function escapeCsv(value) {
  const text = String(value ?? '')
  if (text.includes(',') || text.includes('"') || text.includes('\n')) {
    return `"${text.replaceAll('"', '""')}"`
  }
  return text
}

function csvDownload(state, rows) {
  const metadata = [
    ['fecha_simulada', state.fechaSimulada || '--'],
    ['dias_simulacion', state.totalDias || state.totalDays || 0],
    ['cumplimiento_sla_pct', Number(state.kpis?.cumplimientoSLA ?? state.kpis?.slaCompliance ?? 0).toFixed(2)],
    ['sla_vencidos', state.kpis?.slaVencidos ?? state.kpis?.slaViolated ?? 0],
  ]

  const header = ['aeropuerto', 'recibidas', 'enviadas', 'ocup_prom_pct', 'ocup_max_pct', 'estado', 'sla_cumplido']
  const lines = rows.map((row) => [
    row.aeropuerto,
    row.recib,
    row.enviad,
    row.ocupProm,
    row.ocupMax,
    row.estado,
    row.estado === 'CRÍTICO' ? 'no' : 'si',
  ])

  const content = [
    ...metadata.map(([k, v]) => `${escapeCsv(k)},${escapeCsv(v)}`),
    '',
    header.join(','),
    ...lines.map((line) => line.map(escapeCsv).join(',')),
  ].join('\n')

  const blob = new Blob([`\uFEFF${content}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `tasf_reporte_dia_${state.diaActual || state.currentDay || 0}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

function headingStyle() {
  return {
    fontFamily: 'var(--mono)',
    fontSize: 11,
    color: 'var(--muted)',
    letterSpacing: 2,
    textTransform: 'uppercase',
  }
}

function semaforoLabel(semaforo) {
  if (semaforo === 'rojo') return 'CRÍTICO'
  if (semaforo === 'ambar') return 'ALTO'
  return 'NORMAL'
}

function semaforoColor(semaforo) {
  if (semaforo === 'rojo') return 'var(--red)'
  if (semaforo === 'ambar') return 'var(--amber)'
  return 'var(--green)'
}

export default function ResultadosScreen({ simState }) {
  const isReady = simState?.finalizada === true

  const airports = useMemo(() => getAeropuertos(simState).map((airport) => {
    if (airport.codigoIATA) return airport
    const pct = airport.warehouseCapacity > 0 ? (airport.currentOccupation / airport.warehouseCapacity) * 100 : 0
    const semaforo = pct >= 85 ? 'rojo' : pct >= 60 ? 'ambar' : 'verde'
    return {
      codigoIATA: airport.id,
      ciudad: airport.name,
      continente: airport.continent,
      capacidadAlmacen: Number(airport.warehouseCapacity || 0),
      ocupacionActual: Number(airport.currentOccupation || 0),
      semaforo,
    }
  }), [simState])

  const envios = useMemo(() => getEnvios(simState), [simState])

  const kpis = useMemo(() => {
    const value = getKpis(simState)
    if (value?.maletasEnTransito !== undefined) {
      return {
        enTransito: Number(value.maletasEnTransito || 0),
        entregadas: Number(value.maletasEntregadas || 0),
        cumplimiento: Number(value.cumplimientoSLA || 0),
        vuelosActivos: Number(value.vuelosActivos || 0),
        slaVencidos: Number(value.slaVencidos || 0),
      }
    }

    return {
      enTransito: Number(value?.bagsInTransit || 0),
      entregadas: Number(value?.bagsDelivered || 0),
      cumplimiento: Number(value?.slaCompliance || 0),
      vuelosActivos: Number(value?.activeFlights || 0),
      slaVencidos: Number(value?.slaViolated || 0),
    }
  }, [simState])

  const airportRows = useMemo(() => airports
    .map((airport) => {
      const pct = airport.capacidadAlmacen ? (airport.ocupacionActual / airport.capacidadAlmacen) * 100 : 0
      return {
        aeropuerto: airport.codigoIATA,
        recib: airport.ocupacionActual,
        enviad: airport.ocupacionActual,
        ocupProm: Number(pct.toFixed(1)),
        ocupMax: Number(pct.toFixed(1)),
        estado: semaforoLabel(airport.semaforo),
        semaforo: airport.semaforo,
      }
    })
    .sort((a, b) => b.ocupMax - a.ocupMax), [airports])

  const cancelaciones = useMemo(() => {
    if (Array.isArray(simState?.logOperaciones)) {
      return getLog(simState).filter((line) => /cancel/i.test(line)).length
    }
    return envios.filter((envio) => envio.estado === 'RETRASADO').length
  }, [simState, envios])

  const replanificaciones = useMemo(() => {
    if (Array.isArray(simState?.logOperaciones)) {
      return getLog(simState).filter((line) => /replan/i.test(line)).length
    }
    return envios.filter((envio) => String(envio.planResumen || '').includes('|')).length
  }, [simState, envios])

  const slaBreakdown = useMemo(() => {
    const continental = envios.filter((envio) => Number(envio.sla || 1) === 1)
    const intercontinental = envios.filter((envio) => Number(envio.sla || 1) > 1)
    const continentalOk = continental.filter((envio) => envio.estado !== 'RETRASADO').length
    const intercontinentalOk = intercontinental.filter((envio) => envio.estado !== 'RETRASADO').length

    return {
      continental: continental.length ? (continentalOk / continental.length) * 100 : 0,
      intercontinental: intercontinental.length ? (intercontinentalOk / intercontinental.length) * 100 : 0,
    }
  }, [envios])

  const totalMaletas = envios.reduce((sum, e) => sum + Number(e.cantidadMaletas || 0), 0)
  const statusColor = kpis.cumplimiento >= 95 ? 'var(--green)' : 'var(--amber)'
  const statusLabel = kpis.cumplimiento >= 95 ? '● SIMULACIÓN COMPLETADA' : '● COMPLETADA CON ALERTAS'

  if (!isReady) {
    return (
      <div style={{
        display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center',
        height: '100%', gap: 8,
      }}>
        <span style={{
          fontFamily: 'var(--mono)', fontSize: 12,
          color: 'var(--muted)', textTransform: 'uppercase',
          letterSpacing: 2,
        }}>SIN RESULTADOS</span>
        <span style={{
          fontFamily: 'var(--mono)', fontSize: 11,
          color: 'var(--muted)'
        }}>
          Ejecute una simulación completa para ver resultados
        </span>
      </div>
    )
  }

  return (
    <div style={{ height: '100%', display: 'grid', gridTemplateColumns: '55% 45%', minHeight: 0 }}>
      <section style={{ borderRight: '1px solid var(--border)', overflowY: 'auto', minHeight: 0 }}>
        <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border)', borderLeft: `3px solid ${statusColor}` }}>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 700, color: statusColor }}>{statusLabel}</div>
          <div style={{ marginTop: 4, color: 'var(--muted)', fontSize: 11 }}>
            Cumplimiento SLA {kpis.cumplimiento.toFixed(1)}% · {kpis.slaVencidos} vencidos · {simState?.totalDays || simState?.totalDias || 0} días
          </div>
        </div>

        <div style={{ borderBottom: '1px solid var(--border)', padding: '14px 18px', display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)' }}>
          {[
            ['Total maletas', totalMaletas],
            ['Entregadas', kpis.entregadas],
            ['Cumpl. SLA', `${kpis.cumplimiento.toFixed(1)}%`],
            ['Cancelaciones', cancelaciones],
            ['Replanificaciones', replanificaciones],
            ['Duración sim', `${simState?.totalDays || simState?.totalDias || 0}d`],
          ].map(([label, value], idx) => (
            <div key={label} style={{ padding: '10px 8px', borderRight: idx % 3 !== 2 ? '1px solid var(--border)' : 'none', borderBottom: idx < 3 ? '1px solid var(--border)' : 'none' }}>
              <div style={{ fontFamily: 'var(--mono)', fontSize: 24, color: 'var(--text-bright)' }}>{value}</div>
              <div style={{ fontSize: 9, color: 'var(--muted)', letterSpacing: 1.5, textTransform: 'uppercase' }}>{label}</div>
            </div>
          ))}
        </div>

        <div style={{ padding: '14px 18px', minHeight: 0 }}>
          <div style={headingStyle()}>Desempeño por aeropuerto</div>
          <div style={{ marginTop: 8, borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 0.7fr 0.7fr 0.9fr 0.9fr 0.9fr', gap: 8, padding: '6px 0', fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: 1.2, color: 'var(--muted)', textTransform: 'uppercase' }}>
              <span>Aeropuerto</span><span style={{ textAlign: 'right' }}>Recib</span><span style={{ textAlign: 'right' }}>Enviad</span><span style={{ textAlign: 'right' }}>Ocup.prom</span><span style={{ textAlign: 'right' }}>Ocup.max</span><span>Estado</span>
            </div>
            {airportRows.map((row) => (
              <div key={row.aeropuerto} style={{ display: 'grid', gridTemplateColumns: '1.1fr 0.7fr 0.7fr 0.9fr 0.9fr 0.9fr', gap: 8, padding: '7px 0', borderTop: '1px solid rgba(255,255,255,0.04)', fontFamily: 'var(--mono)', fontSize: 13 }}>
                <span style={{ color: 'var(--blue)' }}>{row.aeropuerto}</span>
                <span style={{ textAlign: 'right', color: 'var(--text)' }}>{row.recib}</span>
                <span style={{ textAlign: 'right', color: 'var(--text)' }}>{row.enviad}</span>
                <span style={{ textAlign: 'right', color: 'var(--muted)' }}>{row.ocupProm}%</span>
                <span style={{ textAlign: 'right', color: 'var(--muted)' }}>{row.ocupMax}%</span>
                <span style={{ color: semaforoColor(row.semaforo) }}>{row.estado}</span>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section style={{ padding: 18, overflowY: 'auto', minHeight: 0 }}>
        <div>
          <div style={{ marginTop: 10 }}>
            {[
              ['Rutas evaluadas', envios.length || '--'],
              ['Tiempo ejecución', '--'],
              ['Periodo', `${simState?.totalDays || simState?.totalDias || 0} días`],
            ].map(([label, value]) => (
              <div key={label} style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
              <span style={{ color: 'var(--muted)', fontSize: 13 }}>{label}</span>
              <span style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)' }}>{value}</span>
              </div>
            ))}
          </div>
        </div>

        <div style={{ marginTop: 20 }}>
          <div style={headingStyle()}>Análisis SLA</div>
          {[
            ['Continental', slaBreakdown.continental],
            ['Intercontinental', slaBreakdown.intercontinental],
          ].map(([label, value]) => {
            const color = value >= 85 ? 'var(--green)' : value >= 70 ? 'var(--amber)' : 'var(--red)'
            return (
              <div key={label} style={{ marginTop: 10 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ color: 'var(--muted)', fontSize: 13 }}>{label}</span>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)' }}>{Number(value).toFixed(1)}%</span>
                </div>
                <div style={{ width: '100%', height: 6, background: 'var(--border)' }}>
                  <div style={{ width: `${Math.min(100, value)}%`, height: '100%', background: color }} />
                </div>
              </div>
            )
          })}
        </div>

        <div style={{ marginTop: 20 }}>
          <div style={headingStyle()}>Log de operaciones</div>
          <div style={{ marginTop: 8, maxHeight: 160, overflowY: 'auto', border: '1px solid var(--border)', padding: 8 }}>
            {(getLog(simState).length ? getLog(simState) : ['Sin eventos en esta simulación']).map((entry, idx) => {
              const [ts, ...rest] = String(entry).split('|')
              const body = rest.length ? rest.join('|').trim() : ts
              const stamp = rest.length ? ts.trim() : '--'
              return (
                <div key={`${entry}-${idx}`} style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', marginBottom: 6 }}>
                  <span style={{ color: 'rgba(127,127,127,0.9)', marginRight: 8 }}>{stamp}</span>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 13 }}>{body}</span>
                </div>
              )
            })}
          </div>
        </div>

        <button
          onClick={() => csvDownload(simState, airportRows)}
          style={{
            marginTop: 20,
            width: '100%',
            background: 'transparent',
            border: '1px solid var(--border)',
            color: 'var(--text)',
            fontFamily: 'var(--mono)',
            fontSize: 12,
            padding: 10,
            letterSpacing: 1,
            cursor: 'pointer',
          }}
          onMouseEnter={(event) => {
            event.currentTarget.style.borderColor = 'var(--blue)'
            event.currentTarget.style.color = 'var(--blue)'
          }}
          onMouseLeave={(event) => {
            event.currentTarget.style.borderColor = 'var(--border)'
            event.currentTarget.style.color = 'var(--text)'
          }}
        >
          ↓ EXPORTAR REPORTE CSV
        </button>
      </section>
    </div>
  )
}
