import React, { useMemo, useState } from 'react'
import { Bar } from 'react-chartjs-2'
import { Chart, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'
import DrawerAeropuerto from '../drawers/DrawerAeropuerto.jsx'

Chart.register(CategoryScale, LinearScale, BarElement, Tooltip)

const getEnvios = (s) =>
  s?.envios || s?.routes?.map((r) => ({
    idEnvio: r.id,
    codigoAerolinea: r.baggageId,
    aeropuertoOrigen: r.flightLegs?.[0]?.origin,
    aeropuertoDestino: r.flightLegs?.slice(-1)[0]?.destination,
    cantidadMaletas: r.bags,
    estado: r.status === 'green' ? 'ENTREGADO'
      : r.status === 'red' ? 'RETRASADO'
        : 'EN_TRANSITO',
    sla: r.type === 'same' ? 1 : 2,
    planResumen: r.flightLegs?.map((l) =>
      `${l.origin}->${l.destination}`).join(' | '),
    tiempoRestante: r.etaRemaining
      ? `${Math.round(r.etaRemaining)}h` : '—',
  })) || []

const getAeropuertos = (s) =>
  s?.aeropuertos || s?.airports || []

const getKpis = (s) => s?.kpis || {
  maletasEnTransito: s?.kpis?.bagsInTransit || 0,
  maletasEntregadas: s?.kpis?.bagsDelivered || 0,
  cumplimientoSLA: s?.kpis?.slaCompliance || 0,
  vuelosActivos: s?.kpis?.activeFlights || 0,
  slaVencidos: s?.kpis?.slaViolated || 0,
  ocupacionPromedioAlmacen: 0,
}

const getThroughput = (s) =>
  s?.throughputHistorial || s?.throughputHistory || []

const getLog = (s) =>
  s?.logOperaciones || []

function normalizeAirports(simState) {
  return getAeropuertos(simState).map((airport) => {
    if (airport.codigoIATA) {
      return {
        id: airport.codigoIATA,
        city: airport.ciudad,
        continent: airport.continente,
        capacity: Number(airport.capacidadAlmacen || 0),
        occupation: Number(airport.ocupacionActual || 0),
        semaforo: airport.semaforo || 'verde',
        raw: airport,
      }
    }
    const pct = airport.warehouseCapacity > 0 ? (airport.currentOccupation / airport.warehouseCapacity) * 100 : 0
    const semaforo = pct >= 85 ? 'rojo' : pct >= 60 ? 'ambar' : 'verde'
    return {
      id: airport.id,
      city: airport.name,
      continent: airport.continent,
      capacity: Number(airport.warehouseCapacity || 0),
      occupation: Number(airport.currentOccupation || 0),
      semaforo,
      raw: {
        codigoIATA: airport.id,
        nombre: airport.name,
        ciudad: airport.name,
        continente: airport.continent,
        capacidadAlmacen: Number(airport.warehouseCapacity || 0),
        ocupacionActual: Number(airport.currentOccupation || 0),
        semaforo,
        lat: airport.lat,
        lng: airport.lng,
      },
    }
  })
}

function normalizeFlights(simState) {
  if (Array.isArray(simState?.vuelos)) {
    return simState.vuelos.map((flight) => ({
      id: flight.codigoVuelo,
      origen: flight.origen,
      destino: flight.destino,
      carga: Number(flight.cargaActual || 0),
      capacidad: Number(flight.capacidadTotal || 0),
    }))
  }

  return (simState?.flights || []).map((flight) => ({
    origen: flight.origin,
    destino: flight.destination,
    carga: Number(flight.currentLoad || 0),
    capacidad: Number(flight.capacity || 0),
  }))
}

function semaforoColor(semaforo) {
  if (semaforo === 'rojo') return 'var(--red)'
  if (semaforo === 'ambar') return 'var(--amber)'
  return 'var(--green)'
}

export default function DashboardScreen({ simState }) {
  const [selectedAp, setSelectedAp] = useState(null)

  const airports = useMemo(() => normalizeAirports(simState), [simState])
  const flights = useMemo(() => normalizeFlights(simState), [simState])

  const throughput = useMemo(() => {
    if (Array.isArray(simState?.throughputHistorial)) {
      return getThroughput(simState).map((row) => ({
        label: `D${row.dia}`,
        slaOk: Number(row.slaOk || 0),
        slaBreach: Number(row.slaBreach || 0),
      }))
    }
    return getThroughput(simState).map((row) => ({
      label: row.day,
      slaOk: Number(row.slaOk || 0),
      slaBreach: Math.max(0, Number(row.bagsProcessed || 0) - Number(row.slaOk || 0)),
    }))
  }, [simState])

  const kpis = useMemo(() => {
    const value = getKpis(simState)
    if (value?.maletasEnTransito !== undefined) {
      return {
        enTransito: Number(value.maletasEnTransito || 0),
        entregadas: Number(value.maletasEntregadas || 0),
        cumplimiento: Number(value.cumplimientoSLA || 0),
        vuelosActivos: Number(value.vuelosActivos || 0),
        slaVencidos: Number(value.slaVencidos || 0),
        ocupPromedio: Number(value.ocupacionPromedioAlmacen || 0),
      }
    }
    return {
      enTransito: Number(value?.bagsInTransit || 0),
      entregadas: Number(value?.bagsDelivered || 0),
      cumplimiento: Number(value?.slaCompliance || 0),
      vuelosActivos: Number(value?.activeFlights || 0),
      slaVencidos: Number(value?.slaViolated || 0),
      ocupPromedio: airports.length
        ? airports.reduce((acc, airport) => acc + ((airport.capacity ? airport.occupation / airport.capacity : 0) * 100), 0) / airports.length
        : 0,
    }
  }, [simState, airports])

  const chartColors = useMemo(() => {
    const styles = getComputedStyle(document.documentElement)
    return {
      muted: styles.getPropertyValue('--muted').trim() || '#8b949e',
      grid: 'rgba(255,255,255,0.04)',
    }
  }, [simState])

  const chartData = {
    labels: throughput.map((item) => item.label),
    datasets: [
      {
        label: 'SLA OK',
        data: throughput.map((item) => item.slaOk),
        backgroundColor: 'rgba(34,208,122,0.85)',
        borderWidth: 0,
        stack: 'throughput',
      },
      {
        label: 'SLA Breach',
        data: throughput.map((item) => item.slaBreach),
        backgroundColor: 'rgba(240,75,75,0.85)',
        borderWidth: 0,
        stack: 'throughput',
      },
    ],
  }

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: 'rgba(13,17,23,0.95)',
        borderColor: 'rgba(255,255,255,0.12)',
        borderWidth: 1,
      },
    },
    scales: {
      x: {
        stacked: true,
        grid: { color: chartColors.grid },
        ticks: { color: chartColors.muted, font: { family: 'Space Mono', size: 10 } },
      },
      y: {
        stacked: true,
        grid: { color: chartColors.grid },
        ticks: { color: chartColors.muted, font: { family: 'Space Mono', size: 10 } },
      },
    },
  }

  const continentStats = useMemo(() => {
    const index = Object.fromEntries(airports.map((airport) => [airport.id, airport]))
    const groups = ['americas', 'europa', 'asia']

    return groups.map((continent) => {
      const aps = airports.filter((airport) => airport.continent === continent)
      const vuelos = flights.filter((flight) => index[flight.origen]?.continent === continent)
      const ocupProm = aps.length
        ? aps.reduce((acc, airport) => acc + (airport.capacity ? (airport.occupation / airport.capacity) * 100 : 0), 0) / aps.length
        : 0

      return {
        continent,
        airports: aps.length,
        flights: vuelos.length,
        ocupProm,
      }
    })
  }, [airports, flights])

  const airportRows = useMemo(() => {
    return [...airports].sort((a, b) => {
      const pa = a.capacity ? a.occupation / a.capacity : 0
      const pb = b.capacity ? b.occupation / b.capacity : 0
      return pb - pa
    })
  }, [airports])

  const kpiCells = [
    { label: 'En tránsito', value: kpis.enTransito.toLocaleString(), color: 'var(--text-bright)' },
    { label: 'Entregadas', value: kpis.entregadas.toLocaleString(), color: 'var(--green)' },
    { label: 'Cumpl. SLA', value: `${kpis.cumplimiento.toFixed(1)}%`, color: kpis.cumplimiento >= 90 ? 'var(--green)' : kpis.cumplimiento >= 75 ? 'var(--amber)' : 'var(--red)' },
    { label: 'Vuelos activos', value: String(kpis.vuelosActivos), color: 'var(--blue)' },
    { label: 'SLA vencidos', value: String(kpis.slaVencidos), color: kpis.slaVencidos > 0 ? 'var(--red)' : 'var(--muted)' },
    { label: 'Ocup. promedio', value: `${kpis.ocupPromedio.toFixed(1)}%`, color: 'var(--text-bright)' },
  ]

  return (
    <div style={{ height: '100%', display: 'grid', gridTemplateRows: '64px 1fr 180px', gridTemplateColumns: '1fr 1fr 1fr', minHeight: 0 }}>
      <section style={{ gridColumn: '1 / span 3', display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', borderBottom: '1px solid var(--border)', background: 'var(--panel)' }}>
        {kpiCells.map((cell, idx) => (
          <div
            key={cell.label}
            style={{
              padding: '0 20px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'center',
              borderRight: idx < kpiCells.length - 1 ? '1px solid var(--border)' : 'none',
            }}
          >
            <div style={{ fontFamily: 'var(--mono)', fontSize: 22, fontWeight: 500, color: cell.color }}>{cell.value}</div>
            <div style={{ textTransform: 'uppercase', fontSize: 10, color: 'var(--muted)', letterSpacing: 1.5 }}>{cell.label}</div>
          </div>
        ))}
      </section>

      <section style={{ gridColumn: '1 / span 2', borderRight: '1px solid var(--border)', borderBottom: '1px solid var(--border)', padding: 14, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: 2, textTransform: 'uppercase', color: 'var(--muted)', marginBottom: 10 }}>Throughput diario</div>
        <div style={{ minHeight: 0, flex: 1 }}>
          <Bar data={chartData} options={chartOptions} />
        </div>
        <div style={{ display: 'flex', gap: 16, marginTop: 8 }}>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 10, height: 10, background: 'rgba(34,208,122,0.85)', display: 'inline-block' }} /> SLA OK
          </div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 10, height: 10, background: 'rgba(240,75,75,0.85)', display: 'inline-block' }} /> SLA Breach
          </div>
        </div>
      </section>

      <section style={{ borderBottom: '1px solid var(--border)', padding: 14, overflowY: 'auto', minHeight: 0 }}>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: 2, textTransform: 'uppercase', color: 'var(--muted)', marginBottom: 10 }}>Por continente</div>
        {continentStats.map((item, idx) => {
          const color = item.ocupProm >= 85 ? 'var(--red)' : item.ocupProm >= 60 ? 'var(--amber)' : 'var(--green)'
          return (
            <div
              key={item.continent}
              style={{
                borderBottom: idx < continentStats.length - 1 ? '1px solid var(--border)' : 'none',
                paddingBottom: 10,
                marginBottom: idx < continentStats.length - 1 ? 10 : 0,
              }}
            >
              <div style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)', textTransform: 'uppercase', marginBottom: 8 }}>{item.continent}</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 5 }}><span style={{ color: 'var(--muted)', fontSize: 13 }}>Aeropuertos</span><span style={{ fontFamily: 'var(--mono)', fontSize: 13 }}>{item.airports}</span></div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 5 }}><span style={{ color: 'var(--muted)', fontSize: 13 }}>Vuelos</span><span style={{ fontFamily: 'var(--mono)', fontSize: 13 }}>{item.flights}</span></div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}><span style={{ color: 'var(--muted)', fontSize: 13 }}>Ocup.prom</span><span style={{ fontFamily: 'var(--mono)', fontSize: 13, color }}>{item.ocupProm.toFixed(1)}%</span></div>
            </div>
          )
        })}
      </section>

      <section style={{ gridColumn: '1 / span 3', overflowY: 'auto', minHeight: 0 }}>
        <div style={{ position: 'sticky', top: 0, zIndex: 2, background: 'var(--panel)', borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)', padding: '6px 14px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '0.8fr 1fr 1fr 1.3fr 0.9fr 0.9fr', gap: 10, fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1.5 }}>
            <span>IATA</span><span>Ciudad</span><span>Continente</span><span>Ocupación</span><span>Estado</span><span style={{ textAlign: 'right' }}>Capacidad</span>
          </div>
        </div>

        {airportRows.map((airport) => {
          const pct = airport.capacity ? Math.round((airport.occupation / airport.capacity) * 100) : 0
          const semaforo = airport.semaforo || (pct >= 85 ? 'rojo' : pct >= 60 ? 'ambar' : 'verde')
          const estado = semaforo === 'rojo' ? 'CRÍTICO' : semaforo === 'ambar' ? 'ALTO' : 'NORMAL'
          const color = semaforoColor(semaforo)

          return (
            <div
              key={airport.id}
              onClick={() => setSelectedAp(airport.raw)}
              style={{
                display: 'grid',
                gridTemplateColumns: '0.8fr 1fr 1fr 1.3fr 0.9fr 0.9fr',
                gap: 10,
                padding: '7px 14px',
                borderBottom: '1px solid rgba(255,255,255,0.04)',
                fontFamily: 'var(--mono)',
                fontSize: 13,
                color: 'var(--text)',
                cursor: 'pointer',
                alignItems: 'center',
              }}
              onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(255,255,255,0.03)' }}
              onMouseLeave={(event) => { event.currentTarget.style.background = 'transparent' }}
            >
              <span style={{ color: 'var(--blue)' }}>{airport.id}</span>
              <span>{airport.city}</span>
              <span style={{ color: 'var(--muted)' }}>{airport.continent}</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 80, height: 3, background: 'var(--border)', display: 'inline-block' }}>
                  <span style={{ width: `${Math.min(100, pct)}%`, height: '100%', background: color, display: 'block' }} />
                </span>
                <span style={{ color: 'var(--muted)' }}>{pct}%</span>
              </span>
              <span style={{ color }}>{estado}</span>
              <span style={{ textAlign: 'right', color: 'var(--muted)' }}>{airport.occupation}/{airport.capacity}</span>
            </div>
          )
        })}
      </section>

      <DrawerAeropuerto
        airport={selectedAp}
        vuelos={simState?.vuelos || simState?.flights || []}
        onClose={() => setSelectedAp(null)}
      />
    </div>
  )
}
