import React, { useEffect, useMemo, useRef, useState } from 'react'
import LeftPanel from './components/LeftPanel.jsx'
import MapView from './components/MapView.jsx'
import RightPanel from './components/RightPanel.jsx'
import TopBar from './components/TopBar.jsx'
import { api } from './services/api.js'
import ConfigScreen from './screens/ConfigScreen.jsx'
import EnviosScreen from './screens/EnviosScreen.jsx'
import DashboardScreen from './screens/DashboardScreen.jsx'
import ResultadosScreen from './screens/ResultadosScreen.jsx'
import DrawerAeropuerto from './drawers/DrawerAeropuerto.jsx'
import DrawerVuelo from './drawers/DrawerVuelo.jsx'

export default function App() {
  const ALGORITHM = 'SIMULATED_ANNEALING'
  const SIM_MINUTES_PER_REAL_SECOND = 120 // ~12s per simulated day
  const [simDay, setSimDay] = useState(1)
  const [simHour, setSimHour] = useState(6)
  const [simMin, setSimMin] = useState(0)
  const [running, setRunning] = useState(false)
  const [realElapsedSeconds, setRealElapsedSeconds] = useState(0)

  const [threshold, setThreshold] = useState(80)
  const [theme, setTheme] = useState('dark')
  const [screen, setScreen] = useState('main')
  const [configOpen, setConfigOpen] = useState(false)
  const [backendState, setBackendState] = useState(null)
  const [staticAirports, setStaticAirports] = useState([])

  const [filters, setFilters] = useState({
    status: ['green', 'amber', 'red'],
    route: ['same', 'inter'],
  })

  const [selectedFlight, setSelectedFlight] = useState(null)
  const [selectedRoute, setSelectedRoute] = useState(null)
  const [mapSelectedAirport, setMapSelectedAirport] = useState(null)
  const [mapSelectedVuelo, setMapSelectedVuelo] = useState(null)
  const [simClockMinutes, setSimClockMinutes] = useState(0)

  const intervalRef = useRef(null)
  const realStartRef = useRef(null)
  const accumulatedRealMsRef = useRef(0)
  const pollingRef = useRef(null)
  const autoStepRef = useRef(null)
  const maxDay = 5

  const [autoStep, setAutoStep] = useState(false)
  const [debugOpen, setDebugOpen] = useState(false)
  const [leftOpen, setLeftOpen] = useState(true)
  const [rightOpen, setRightOpen] = useState(true)

  useEffect(() => {
    function onKey(e) {
      if (e.shiftKey && e.key === 'D') setDebugOpen((v) => !v)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  function stopAutoStep() {
    clearInterval(autoStepRef.current)
  }

  function parseTimeToMinutes(value) {
    if (!value || typeof value !== 'string' || !value.includes(':')) return null
    const [hh, mm] = value.split(':').map((v) => Number(v))
    if (!Number.isFinite(hh) || !Number.isFinite(mm)) return null
    return hh * 60 + mm
  }

  function isActiveAtMinute(nowMin, depMin, arrMin) {
    if (depMin == null || arrMin == null) return false
    if (arrMin > depMin) {
      return nowMin >= depMin && nowMin < arrMin
    }
    return nowMin >= depMin || nowMin < arrMin
  }

  function flightFractionAtMinute(nowMin, depMin, arrMin) {
    const total = (arrMin - depMin + 1440) % 1440
    if (total <= 0) return 0
    const elapsed = (nowMin - depMin + 1440) % 1440
    return Math.max(0, Math.min(1, elapsed / total))
  }

  function onToggleSim() {
    setAutoStep((prev) => !prev)
  }

  function onReset() {
    setRunning(false)
    setAutoStep(false)
    setSimDay(1)
    setSimHour(6)
    setSimMin(0)
    realStartRef.current = null
    accumulatedRealMsRef.current = 0
    setRealElapsedSeconds(0)
    setSelectedFlight(null)
    setSelectedRoute(null)
    setConfigOpen(false)
    setScreen('main')
  }

  function stopPolling() {
    if (pollingRef.current) {
      clearInterval(pollingRef.current)
      pollingRef.current = null
    }
  }

  function startPolling() {
    stopPolling()
    pollingRef.current = setInterval(async () => {
      try {
        const state = await api.getState()
        // Only update state if backend has real data or is actively running/finished.
        // Prevents empty post-reset state from overwriting a valid finalizada snapshot.
        if (state && (state.enEjecucion || state.finalizada)) {
          setBackendState(state)
          if (state.finalizada) {
            stopPolling()
            setScreen('resultados')
          }
        }
      } catch (err) {
        console.error('Polling error:', err)
      }
    }, 2000)
  }

  function onIniciar() {
    if (!backendState) {
      setScreen('config')
      setConfigOpen(true)
      return
    }
    if (autoStep) {
      onToggleSim()
    }
  }

  function onToggleTheme() {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'))
  }

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  useEffect(() => {
    api.getAirports()
      .then((data) => setStaticAirports(
        data.map((airport) => ({
          ...airport,
          id: airport.codigoIATA,
          name: airport.nombre,
          continent: airport.continent || airport.continente,
          currentOccupation: airport.ocupacionActual ?? 0,
          warehouseCapacity: airport.capacidadAlmacen ?? 600,
        }))
      ))
      .catch(() => {})
  }, [])

  useEffect(() => {
    return () => stopPolling()
  }, [])

  useEffect(() => {
    if (backendState?.enEjecucion && !backendState?.finalizada) {
      setAutoStep(true)
    }
    if (backendState?.finalizada) {
      setAutoStep(false)
      stopAutoStep()
    }
  }, [backendState?.enEjecucion, backendState?.finalizada])

  useEffect(() => {
    if (autoStep) {
      autoStepRef.current = setInterval(async () => {
        setSimClockMinutes((current) => {
          const next = current + SIM_MINUTES_PER_REAL_SECOND
          return Math.min(next, 1440)
        })
      }, 1000)
    } else {
      clearInterval(autoStepRef.current)
    }
    return () => clearInterval(autoStepRef.current)
  }, [autoStep])

  useEffect(() => {
    if (!autoStep) return
    if (simClockMinutes < 1440) return

    let cancelled = false
    ;(async () => {
      try {
        const newState = await api.stepSimulation()
        if (cancelled) return
        if (newState) {
          setBackendState(newState)
          setSimClockMinutes(0)
          if (newState.finalizada) {
            setAutoStep(false)
            clearInterval(autoStepRef.current)
            stopPolling()
            setScreen('resultados')
          }
        }
      } catch (err) {
        console.error('Auto-step error:', err)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [simClockMinutes, autoStep])

  useEffect(() => {
    if (autoStep && realStartRef.current === null) {
      realStartRef.current = Date.now()
    }
    if (!autoStep && realStartRef.current !== null) {
      accumulatedRealMsRef.current += Date.now() - realStartRef.current
      realStartRef.current = null
      setRealElapsedSeconds(Math.floor(accumulatedRealMsRef.current / 1000))
    }
  }, [autoStep])

  useEffect(() => {
    if (!autoStep) return undefined
    const id = setInterval(() => {
      const liveMs = accumulatedRealMsRef.current + (Date.now() - realStartRef.current)
      setRealElapsedSeconds(Math.floor(liveMs / 1000))
    }, 250)
    return () => clearInterval(id)
  }, [autoStep])

  useEffect(() => {
    if (running) {
      intervalRef.current = setInterval(() => {
        setSimMin((currentMinutes) => {
          if (currentMinutes + 3 >= 60) {
            setSimHour((currentHour) => {
              if (currentHour + 1 >= 24) {
                setSimDay((currentDay) => {
                  if (currentDay + 1 > maxDay) {
                    setRunning(false)
                    return currentDay
                  }
                  return currentDay + 1
                })
                return 0
              }
              return currentHour + 1
            })
            return 0
          }
          return currentMinutes + 3
        })
      }, 100)
    } else {
      clearInterval(intervalRef.current)
    }
    return () => clearInterval(intervalRef.current)
  }, [running, maxDay])

  const simState = backendState ?? {
    currentDay: 0, totalDays: 0,
    elapsedSeconds: 0, algorithm: ALGORITHM,
    kpis: {
      bagsInTransit: 0, bagsDelivered: 0,
      slaCompliance: 0, activeFlights: 0,
      slaViolated: 0,
    },
    airports: staticAirports,
    flights: [], routes: [],
    throughputHistory: [], logOperaciones: [],
  }

  const normalizedAirports = (simState?.airports || simState?.aeropuertos || []).map((airport) => ({
    ...airport,
    id: airport.id || airport.codigoIATA,
    name: airport.name || airport.nombre,
    continent: airport.continent || airport.continente,
    lat: airport.lat,
    lng: airport.lng,
    currentOccupation: airport.currentOccupation ?? airport.ocupacionActual ?? 0,
    warehouseCapacity: airport.warehouseCapacity ?? airport.capacidadAlmacen ?? 600,
  }))

  const normalizedFlights = simState?.vuelos
    ? simState.vuelos.map((flight, idx) => ({
      id: flight.id || flight.codigoVuelo || `FL-${idx}`,
      origin: flight.origin || flight.origen,
      destination: flight.destination || flight.destino,
      type: flight.type || flight.tipo || 'intercontinental',
      status: (flight.status || flight.estado) === 'cancelado'
        ? 'cancelled'
        : (flight.status || flight.estado) === 'completado'
          ? 'completed'
          : 'active',
      currentLoad: flight.currentLoad ?? flight.cargaActual ?? 0,
      capacity: flight.capacity ?? flight.capacidadTotal ?? 300,
      hour: Number((flight.horaSalida || '00:00').split(':')[0]),
      fraction: flight.fraction ?? 0,
    }))
    : (simState?.flights || [])

  const normalizedRoutes = simState?.envios
    ? simState.envios.map((envio, idx) => ({
      id: envio.idEnvio || `RT-${idx}`,
      status: envio.estado === 'RETRASADO' ? 'red' : envio.estado === 'ENTREGADO' ? 'green' : 'amber',
      replanified: false,
      bags: envio.cantidadMaletas || 0,
      type: Number(envio.sla || 1) > 1 ? 'inter' : 'same',
      flightLegs: [{ origin: envio.aeropuertoOrigen, destination: envio.aeropuertoDestino }],
      etaRemaining: 0,
    }))
    : (simState?.routes || [])

  const backendRoutes = useMemo(() => {
    if (!backendState?.envios) return []
    return backendState.envios
      .filter((e) => e.planResumen)
      .map((e) => {
        const legs = (e.planResumen || '')
          .split('|')
          .map((leg) => leg.trim())
          .filter(Boolean)
          .map((leg) => {
            const [origin, destination] = leg.split('->')
            return { origin: origin?.trim(), destination: destination?.trim() }
          })
          .filter((l) => l.origin && l.destination)

        const status =
          e.estado === 'ENTREGADO' ? 'green' :
          e.estado === 'RETRASADO' ? 'red' : 'amber'

        return {
          id: e.idEnvio,
          status,
          type: e.sla === 1 ? 'same' : 'inter',
          bags: e.cantidadMaletas,
          flightLegs: legs,
          replanified: false,
        }
      })
      .filter((r) => r.flightLegs.length > 0)
  }, [backendState?.envios])

  const backendFlights = useMemo(() => {
    if (!backendState?.vuelos) return []
    return backendState.vuelos
      .filter((v) => v.estado === 'activo' && v.enUso)
      .map((v) => {
        const depMin = parseTimeToMinutes(v.horaSalida)
        const arrMin = parseTimeToMinutes(v.horaLlegada)
        if (!isActiveAtMinute(simClockMinutes, depMin, arrMin)) return null
        return {
          id: v.codigoVuelo,
          origin: v.origen,
          destination: v.destino,
          currentLoad: v.maletasAsignadas ?? v.cargaActual ?? 0,
          capacity: v.capacidadTotal ?? 300,
          type: v.tipo === 'continental' ? 'continental' : 'intercontinental',
          status: 'active',
          fraction: flightFractionAtMinute(simClockMinutes, depMin, arrMin),
        }
      })
      .filter(Boolean)
  }, [backendState?.vuelos, simClockMinutes])

  const fechaSimuladaDisplay = useMemo(() => {
    if (!backendState?.fechaSimulada) return null
    const source = new Date(backendState.fechaSimulada)
    if (Number.isNaN(source.getTime())) return backendState.fechaSimulada

    source.setHours(0, 0, 0, 0)
    const current = new Date(source.getTime() + simClockMinutes * 60000)
    const yyyy = current.getFullYear()
    const mm = String(current.getMonth() + 1).padStart(2, '0')
    const dd = String(current.getDate()).padStart(2, '0')
    const hh = String(current.getHours()).padStart(2, '0')
    const mi = String(current.getMinutes()).padStart(2, '0')
    return `${yyyy}-${mm}-${dd} ${hh}:${mi}`
  }, [backendState?.fechaSimulada, simClockMinutes])

  useEffect(() => {
    if (!selectedFlight) {
      setMapSelectedVuelo(null)
      return
    }
    const vuelo = backendFlights.find((f) => f.id === selectedFlight)
    if (vuelo) setMapSelectedVuelo(vuelo)
    // If vuelo not found in current frame, keep the previous drawer content open.
  }, [selectedFlight, backendState, backendFlights])

  const activeKpis = backendState?.kpis
    ? {
        bagsInTransit: backendState.kpis.maletasEnTransito,
        bagsDelivered: backendState.kpis.maletasEntregadas,
        slaCompliance: backendState.kpis.cumplimientoSLA,
        activeFlights: backendState.kpis.vuelosActivos,
        slaViolated: backendState.kpis.slaVencidos,
      }
    : simState?.kpis ?? {
        bagsInTransit: 0, bagsDelivered: 0,
        slaCompliance: 0, activeFlights: 0,
        slaViolated: 0,
      }

  async function handleReset() {
    try {
      await api.resetSimulation()
    } catch (err) {
      console.error('Reset backend error:', err)
    }
    stopPolling()
    onReset()
    setBackendState(null)
  }

  return (
    <>
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden', background: 'var(--bg)' }}>
      <TopBar
        currentDay={backendState?.diaActual ?? 0}
        totalDays={backendState?.totalDias ?? 0}
        elapsedSeconds={backendState?.diaActual ? backendState.diaActual * 86400 : 0}
        fechaSimulada={fechaSimuladaDisplay}
        realElapsedSeconds={realElapsedSeconds}
        simRateLabel={null}
        kpis={activeKpis}
        isRunning={autoStep}
        running={running}
        backendState={backendState}
        onToggleSim={onToggleSim}
        onReset={handleReset}
        theme={theme}
        onToggleTheme={onToggleTheme}
        onNavigate={(next) => {
          setConfigOpen(false)
          setScreen(next)
        }}
        onIniciar={onIniciar}
        screen={screen}
        hasSimulation={Boolean(backendState)}
      />
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative', minHeight: 0 }}>
        {/* ── OPERACIONES (main map view) ─────────────────────────────── */}
        {/* ── OPERACIONES (main map view) ─────────────────────────────── */}
        {(screen === 'main' && !configOpen) && (
          <div style={{
            display: 'grid',
            gridTemplateColumns: `${leftOpen ? '220px' : '0px'} 1fr ${rightOpen ? '300px' : '0px'}`,
            height: '100%',
            overflow: 'hidden',
            transition: 'grid-template-columns 0.4s cubic-bezier(0.4, 0, 0.2, 1)'
          }}>
            {/* Left Panel Container */}
            <div style={{ overflow: 'hidden', borderRight: leftOpen ? '1px solid var(--border)' : 'none', background: 'var(--panel)' }}>
              <LeftPanel filters={filters} setFilters={setFilters} threshold={threshold} setThreshold={setThreshold} />
            </div>

            {/* Center Map Container */}
            <div style={{ position: 'relative', height: '100%', overflow: 'hidden' }}>
              {/* Floating Toggle Buttons */}
              <button
                onClick={() => setLeftOpen(!leftOpen)}
                style={{
                  position: 'absolute', left: 0, top: '50%', transform: 'translateY(-50%)',
                  zIndex: 1000, width: 24, height: 48, background: 'rgba(13, 17, 23, 0.85)',
                  border: '1px solid var(--border)', borderLeft: 'none', borderRadius: '0 8px 8px 0',
                  color: 'white', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center'
                }}
              >
                {leftOpen ? '‹' : '›'}
              </button>

              <MapView
                airports={normalizedAirports}
                routes={backendRoutes}
                flights={backendFlights}
                filters={filters}
                threshold={threshold}
                simHour={simHour}
                simMin={simMin}
                selectedRoute={selectedRoute}
                setSelectedRoute={setSelectedRoute}
                selectedFlight={selectedFlight}
                setSelectedFlight={setSelectedFlight}
                onFlightFromRoute={setMapSelectedVuelo}
                onAirportClick={setMapSelectedAirport}
                theme={theme}
              />

              <button
                onClick={() => setRightOpen(!rightOpen)}
                style={{
                  position: 'absolute', right: 0, top: '50%', transform: 'translateY(-50%)',
                  zIndex: 1000, width: 24, height: 48, background: 'rgba(13, 17, 23, 0.85)',
                  border: '1px solid var(--border)', borderRight: 'none', borderRadius: '8px 0 0 8px',
                  color: 'white', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center'
                }}
              >
                {rightOpen ? '›' : '‹'}
              </button>

              {/* Detail Drawers */}
              <DrawerAeropuerto
                airport={mapSelectedAirport}
                vuelos={backendState?.vuelos || []}
                onClose={() => setMapSelectedAirport(null)}
              />
              <DrawerVuelo
                vuelo={mapSelectedVuelo}
                onClose={() => setMapSelectedVuelo(null)}
              />
            </div>

            {/* Right Panel Container */}
            <div style={{ overflow: 'hidden', borderLeft: rightOpen ? '1px solid var(--border)' : 'none', background: 'var(--panel)' }}>
              <RightPanel
                flights={backendFlights}
                airports={normalizedAirports}
                threshold={threshold}
                selectedFlight={selectedFlight}
                setSelectedFlight={setSelectedFlight}
                onVueloClick={setMapSelectedVuelo}
              />
            </div>
          </div>
        )}

        {/* ── OVERLAY SCREENS (replace the map entirely, no z-index fighting) ── */}
        {(screen !== 'main' || configOpen) && (
          <div style={{ height: '100%', overflow: 'auto', background: 'var(--bg)' }}>
            {screen === 'envios' && (
              <EnviosScreen
                simState={simState}
                theme={theme}
                onBack={() => setScreen('main')}
              />
            )}
            {screen === 'dashboard' && (
              <DashboardScreen
                simState={simState}
                theme={theme}
                onBack={() => setScreen('main')}
              />
            )}
            {screen === 'resultados' && (
              <ResultadosScreen
                simState={simState}
                theme={theme}
                onBack={() => setScreen('main')}
              />
            )}
            {screen === 'config' && (
              <ConfigScreen
                onCancel={() => {
                  setScreen('main')
                  setConfigOpen(false)
                }}
                onSimulationStarted={(state) => {
                  setConfigOpen(false)
                  setBackendState(state)
                  setSimClockMinutes(0)
                  setScreen('main')
                  startPolling()
                }}
              />
            )}
          </div>
        )}
      </div>
    </div>
    {debugOpen && (() => {
      const allVuelos = backendState?.vuelos || []
      const activos = allVuelos.filter((v) => v.estado === 'activo')
      const enUso   = activos.filter((v) => v.enUso)
      const depMin0 = enUso.map((v) => parseTimeToMinutes(v.horaSalida))
      const arrMin0 = enUso.map((v) => parseTimeToMinutes(v.horaLlegada))
      const enAire  = enUso.filter((_, i) => isActiveAtMinute(simClockMinutes, depMin0[i], arrMin0[i]))
      const enviosConPlan = (backendState?.envios || []).filter((e) => e.planResumen && !e.planResumen.includes('no route'))
      const samplePlan   = (backendState?.envios || []).find((e) => e.planResumen)
      const rows = [
        ['backendState',        backendState ? '✓' : 'null'],
        ['vuelos total',        allVuelos.length],
        ['  activos',          activos.length],
        ['  enUso (flag)',      enUso.length],
        ['  maletasAsign > 0', activos.filter((v) => (v.maletasAsignadas ?? 0) > 0).length],
        ['  en aire ahora',    enAire.length],
        ['envios total',        (backendState?.envios || []).length],
        ['  con plan',          enviosConPlan.length],
        ['simClockMinutes',     simClockMinutes],
        ['backendFlights',      backendFlights.length],
        ['autoStep',           String(autoStep)],
        ['samplePlanResumen',   samplePlan?.planResumen ?? 'none'],
      ]
      return (
        <div style={{ position: 'fixed', bottom: 12, left: 12, zIndex: 9999, background: 'rgba(0,0,0,0.88)', border: '1px solid rgba(88,166,255,0.35)', borderRadius: 8, padding: '12px 16px', fontFamily: 'monospace', fontSize: 11, color: '#aac', minWidth: 260, backdropFilter: 'blur(6px)' }}>
          <div style={{ color: '#58a6ff', fontWeight: 700, marginBottom: 8, letterSpacing: 1 }}>DEBUG  <span style={{ color: '#555', fontWeight: 400 }}>Shift+D to close</span></div>
          {rows.map(([k, v]) => (
            <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 16, lineHeight: 1.7 }}>
              <span style={{ color: '#888' }}>{k}</span>
              <span style={{ color: typeof v === 'number' && v > 0 ? '#22d07a' : typeof v === 'number' ? '#f04b4b' : '#e6edf3', fontWeight: 600 }}>{String(v)}</span>
            </div>
          ))}
        </div>
      )
    })()}
  </>
  )
}
