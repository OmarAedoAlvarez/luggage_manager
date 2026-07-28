import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import MapView from './components/MapView.jsx'
import SidePanel from './components/SidePanel.jsx'
import TopBar from './components/TopBar.jsx'
import FloatingKPIs from './components/FloatingKPIs.jsx'
import FloatingClocks from './components/FloatingClocks.jsx'
import FloatingFlightInfo from './components/FloatingFlightInfo.jsx'
import DraggableWidget from './components/DraggableWidget.jsx'
import { api } from './services/api.js'
import ConfigScreen from './screens/ConfigScreen.jsx'
import DashboardScreen from './screens/DashboardScreen.jsx'
import ResultadosScreen from './screens/ResultadosScreen.jsx'
import ColapsoScreen from './screens/ColapsoScreen.jsx'
import LiveScreen from './screens/LiveScreen.jsx'
import OpsScreen from './screens/OpsScreen.jsx'
import DrawerAeropuerto from './drawers/DrawerAeropuerto.jsx'
import DrawerVuelo from './drawers/DrawerVuelo.jsx'
import { getLiveState, getOpsState, getOpsOccupancy, planificarOps, getOpsEnvios, getOpsReporte } from './services/api.js'

// The backend's tiempoRestante is computed against its own fechaSimulada, which is pinned to
// 00:00 of the current día for the whole day's processing (the engine resolves an entire day
// in one /step call, so it has no "instant within the day" of its own) — an envío ingresado late
// in the day would show up to ~24h more remaining than its real SLA. The frontend already has an
// accurate per-minute animated clock (currentSimTime) for estado, so recompute UT from it too.
function computeTiempoRestante(envio, currentSimTime) {
  if (!currentSimTime || !envio.fechaHoraIngreso || envio.sla == null) return envio.tiempoRestante ?? null
  const ingreso = new Date(envio.fechaHoraIngreso)
  const deadline = new Date(ingreso.getTime() + envio.sla * 86400000)
  const diffMs = deadline.getTime() - currentSimTime.getTime()
  if (diffMs < 0) {
    return `vencido ${Math.floor(-diffMs / 3600000)}h`
  }
  const days = Math.floor(diffMs / 86400000)
  const hours = Math.floor((diffMs - days * 86400000) / 3600000)
  return `${days}d ${hours}h`
}

function parseUtcDateTime(value) {
  if (!value || typeof value !== 'string') return null
  const iso = /[Zz]$|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

function deriveOpsEnvioEstado(envio, currentNow) {
  const backendEstado = (envio.estado || '').toUpperCase()
  if (['CANCELADO', 'RETRASADO', 'ENTREGADO'].includes(backendEstado)) return backendEstado

  const firstDeparture = parseUtcDateTime(envio.fechaSalidaPrimerVuelo)
  const lastArrival = parseUtcDateTime(envio.fechaLlegadaUltimoVuelo)
  if (!currentNow || !firstDeparture || !lastArrival) return backendEstado || 'PENDIENTE'

  if (currentNow < firstDeparture) {
    return backendEstado === 'PENDIENTE' ? 'PENDIENTE' : 'PLANIFICADO'
  }

  if (currentNow >= lastArrival) {
    return backendEstado || 'PLANIFICADO'
  }

  return 'EN_TRANSITO'
}

export default function App() {
  const ALGORITHM = 'SIMULATED_ANNEALING'
  const SIM_MINUTES_PER_REAL_SECOND = 1  // 1 min/tick @ 250ms = ~6min per simulated day → 30min for 5 days
  // 4 ticks/sec × 1 min/tick = 4 simulated minutes per real second
  const SIM_MIN_PER_REAL_SEC = SIM_MINUTES_PER_REAL_SECOND * 4
  const [simStartedAt, setSimStartedAt] = useState(null)  // wall-clock ms when sim started

  const [threshold, setThreshold] = useState(80)
  const [theme, setTheme] = useState('dark')
  const [screen, setScreen] = useState('main')
  const [configOpen, setConfigOpen] = useState(false)
  const [backendState, setBackendState] = useState(null)
  const [lastParams, setLastParams] = useState(null)
  const [isRestarting, setIsRestarting] = useState(false)
  const [isOwner, setIsOwner] = useState(false)
  const [staticAirports, setStaticAirports] = useState([])
  const [airportGraph, setAirportGraph] = useState(null)
  const [originIds, setOriginIds] = useState(null)
  const [destIds, setDestIds] = useState(null)

  const [selectedFlight, setSelectedFlight] = useState(null)
  const [flightSource, setFlightSource] = useState(null) // 'map' | 'panel'
  const [mapSelectedAirport, setMapSelectedAirport] = useState(null)
  const [mapSelectedVuelo, setMapSelectedVuelo] = useState(null)
  const [highlightedRoute, setHighlightedRoute] = useState(null)
  const [simClockMinutes, setSimClockMinutes] = useState(0)
  // Mirror of simClockMinutes for stable access inside the polling closure (which is
  // created once in startPolling and would otherwise capture a stale clock value).
  const simClockMinutesRef = useRef(0)
  const [mapFlyTo, setMapFlyTo] = useState(null)
  const [vueloMapFilter, setVueloMapFilter] = useState({ origin: '', dest: '', semaforo: [], query: '' })
  const [airportMapFilter, setAirportMapFilter] = useState({ continent: '', pattern: '', semaforo: [] })

  const realStartRef = useRef(null)  // kept for legacy compat, unused
  const accumulatedRealMsRef = useRef(0)  // kept for legacy compat, unused
  const pollingRef = useRef(null)
  const autoStepRef = useRef(null)
  const pollingErrorsRef = useRef(0)
  const pollInFlightRef = useRef(false)
  const stepInProgressRef = useRef(false)
  const nextDayStateRef = useRef(null)
  const prefetchFiredRef = useRef(false)
  const colapsoPuntoAlertedRef = useRef(false)
  const simStartMinuteRef = useRef(0)
  // Clock cap for the CURRENT day. Normal days end at midnight (1440). The LAST day runs an
  // extra horaInicio minutes past midnight (→ 1440 + simStartMinute) so the total elapsed is a
  // full N×24h measured from día 1 horaInicio (e.g. 08:00), ending at día N+1 horaInicio
  // (día 6 08:00 = 120h) instead of stopping at midnight (día 6 00:00 = 112h). Read by the
  // ticker/visibility handlers which only depend on [autoStep] and can't see fresh backendState.
  const dayClockCapRef = useRef(1440)
  const prevSimStateRef = useRef(null)
  // Envio delta-protocol cache: last full envios array + the version it corresponds to.
  const lastEnviosRef = useRef(null)
  const lastEnviosVersionRef = useRef(-1)

  useEffect(() => {
    if (backendState && !backendState.finalizada) {
      prevSimStateRef.current = backendState
    }
  }, [backendState])
  // Tracks whether this browser tab has ever seen an active simulation.
  // Used to detect when a simulation is cancelled by another tab/user.
  const wasSimRunningRef = useRef(false)

  const [pollingError, setPollingError] = useState(null)

  const [liveState, setLiveState] = useState(null)
  const livePollingRef = useRef(null)
  const liveApplyRef = useRef(null)
  const liveWindowStartRef = useRef(null)
  const liveNextStateRef = useRef(null)

  const [opsState, setOpsState] = useState(null)
  const [opsEnvios, setOpsEnvios] = useState([])
  const [opsReporte, setOpsReporte] = useState(null)
  const opsPollingRef = useRef(null)
  const opsOccRef = useRef(null)

  const [autoStep, setAutoStep] = useState(false)
  const [debugOpen, setDebugOpen] = useState(false)
  const [activeSideSection, setActiveSideSection] = useState(null)
  // Bumped with a fresh object each time the top bar should force-open a section inside
  // OpsScreen's own side panel (which owns its activeSideSection state independently).
  const [opsOpenSectionRequest, setOpsOpenSectionRequest] = useState(null)

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
    // If it's a LocalDateTime, split by 'T' and take the time part
    const timePart = value.includes('T') ? value.split('T')[1] : value
    const parts = timePart.split(':')
    const hh = Number(parts[0])
    const mm = Number(parts[1])
    if (!Number.isFinite(hh) || !Number.isFinite(mm)) return null
    return hh * 60 + mm
  }

  function isActiveAtMinute(nowMin, depMin, arrMin, day) {
    if (depMin == null || arrMin == null) return false
    const m = nowMin >= 1440 ? 1439 : nowMin
    if (arrMin > depMin) {
      return m >= depMin && m < arrMin
    }
    // For overnight flights (arrMin < depMin), m < arrMin means the flight
    // departed yesterday and is arriving today. On Day 1, there was no yesterday!
    if (day <= 1 && m < arrMin) {
      return m >= depMin
    }
    return m >= depMin || m < arrMin
  }

  function evalOccupancyAtMinute(baseline, eventos, minuto) {
    let occ = baseline ?? 0
    for (const ev of eventos || []) {
      if (ev.minuto <= minuto) occ += ev.delta
    }
    return Math.max(0, occ)
  }

  function flightFractionAtMinute(nowMin, depMin, arrMin) {
    const total = (arrMin - depMin + 1440) % 1440
    if (total <= 0) return 0
    const elapsed = (nowMin - depMin + 1440) % 1440
    return Math.max(0, Math.min(1, elapsed / total))
  }


  function onReset() {
    setAutoStep(false)
    dayClockCapRef.current = 1440
    setSimStartedAt(null)
    setSelectedFlight(null)
    setMapSelectedAirport(null)
    setMapSelectedVuelo(null)
    setHighlightedRoute(null)
    setConfigOpen(false)
    setScreen('main')
    setSimClockMinutes(0)
  }

  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current)
      pollingRef.current = null
    }
  }, [])

  // Envio delta protocol: the polled /state is LIGHT (no envios) and carries only
  // enviosVersion. We keep the last full envios array cached and only refetch /envios
  // when the version changes (day advance, replanning, cancellations). Full responses
  // (start/step/restart/cancel) already carry envios, so we just cache them.
  const hydrateEnvios = useCallback(async (state) => {
    if (!state) return state
    const v = state.enviosVersion
    const hasFull = Array.isArray(state.envios) && state.envios.length > 0
    if (hasFull) {
      lastEnviosRef.current = state.envios
      if (v != null) lastEnviosVersionRef.current = v
    } else if (v != null && v !== lastEnviosVersionRef.current) {
      try {
        const envios = await api.getEnvios()
        lastEnviosRef.current = envios
        lastEnviosVersionRef.current = v
      } catch (e) {
        console.error('Error refetching envios (delta):', e)
      }
    }
    state.envios = lastEnviosRef.current || []
    return state
  }, [])

  // Keep the polling-closure's clock mirror current.
  useEffect(() => {
    simClockMinutesRef.current = simClockMinutes
  }, [simClockMinutes])

  const startPolling = useCallback(() => {
    stopPolling()
    pollingErrorsRef.current = 0
    setPollingError(null)
    const id = setInterval(async () => {
      // Skip if a previous poll is still in flight — prevents request pileup/cancel
      // when the state download is slower than the 2s interval (slow VM uplink).
      if (pollInFlightRef.current) return
      pollInFlightRef.current = true
      try {
        const state = await api.getState(simClockMinutesRef.current)

        // Ignore stale responses if this polling session was stopped or restarted
        if (pollingRef.current !== id) return

        // The polled /state is light (no envios). Refetch the envios table only when the
        // backend's enviosVersion changed; otherwise reuse the cached array.
        if (state && (state.enEjecucion || state.finalizada)) {
          await hydrateEnvios(state)
          if (pollingRef.current !== id) return  // may have stopped during the refetch
        }

        pollingErrorsRef.current = 0
        setPollingError(null)
        // Only update state if backend has real data or is actively running/finished.
        // Prevents empty post-reset state from overwriting a valid finalizada snapshot.
        if (state && (state.enEjecucion || state.finalizada) && !stepInProgressRef.current) {
          if (!wasSimRunningRef.current) {
            // New simulation detected while B had no simulation — sync ownership and clock.
            // Clock sync uses the server-provided diaInicioTimestampUtc anchor (not
            // localStorage) so it works across different browsers/machines, not just tabs.
            const owned = localStorage.getItem('simOwner') === '1'
            setIsOwner(owned || Boolean(state.finalizada))
            simStartMinuteRef.current = state.horaInicioMin || 0
            if (state.diaInicioTimestampUtc && state.enEjecucion && !state.finalizada) {
              const dayStartMinute = state.diaActual <= 1 ? (state.horaInicioMin || 0) : 0
              const elapsed = ((Date.now() - state.diaInicioTimestampUtc) / 1000) * SIM_MIN_PER_REAL_SEC
              // Last day runs past midnight to horaInicio (día N+1) so the elapsed reaches N×24h;
              // other days clamp just below midnight so the ticker (not the poll) triggers /step.
              const isLastDay = (state.diaActual || 1) >= (state.totalDias || state.totalDays || 5)
              const ceiling = isLastDay ? (1440 + (state.horaInicioMin || 0)) : 1439
              setSimClockMinutes(Math.min(dayStartMinute + elapsed, ceiling))
            }
          }
          wasSimRunningRef.current = true
          setBackendState(state)
          if (state.finalizada) {
            stopPolling()
            setScreen('resultados')
          }
        } else if (wasSimRunningRef.current && (!state || (!state.enEjecucion && !state.finalizada))) {
          // Simulation was cancelled externally (e.g., by another user pressing CANCELAR).
          // Triggers when state is null (backend cleared) OR flags are both false.
          // Perform a frontend-only reset — do NOT call api.resetSimulation() again.
          wasSimRunningRef.current = false
          clearInterval(autoStepRef.current)
          setAutoStep(false)
          setBackendState(null)
          setSimClockMinutes(0)
          setSelectedFlight(null)
          setMapSelectedAirport(null)
          setMapSelectedVuelo(null)
          setHighlightedRoute(null)
          setConfigOpen(false)
          setActiveSideSection(null)
          setScreen('main')
          localStorage.removeItem('simOwner')
          setIsOwner(false)
          // Refresh airport data to reflect post-reset warehouse occupancy
          refreshStaticAirports()
        }
      } catch (err) {
        pollingErrorsRef.current += 1
        if (pollingErrorsRef.current >= 3) {
          setPollingError('No se puede contactar el servidor')
        }
        console.error('Polling error:', err)
      } finally {
        if (pollingRef.current === id) {
          pollInFlightRef.current = false
        }
      }
    }, 2000)
    pollingRef.current = id
  }, [stopPolling, hydrateEnvios])

  // When simulation finishes, unlock all users (anyone can start next sim)
  useEffect(() => {
    if (backendState?.finalizada) {
      setIsOwner(true)
    }
  }, [backendState?.finalizada])

  // Keep the current day's clock cap in sync. Only the last day extends past midnight so the
  // elapsed clock reaches the true N×24h mark (see dayClockCapRef doc).
  useEffect(() => {
    const isLastDay = !!backendState
      && (backendState.diaActual || 1) >= (backendState.totalDias || backendState.totalDays || 5)
    dayClockCapRef.current = 1440 + (isLastDay ? (simStartMinuteRef.current || 0) : 0)
  }, [backendState?.diaActual, backendState?.totalDias, backendState?.totalDays])

  function onIniciar() {
    if (!backendState) {
      setActiveSideSection('config')
      return
    }
    if (autoStep) {
      setAutoStep(false)
    }
  }

  function onToggleTheme() {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'))
  }

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  useEffect(() => {
    api.getAirportGraph().then(setAirportGraph).catch(() => {})
  }, [])

  // On mount: check if a simulation is already running (another tab/user started it).
  // Always start polling so B detects new simulations started by A after B has loaded.
  useEffect(() => {
    api.getState(simClockMinutesRef.current).then(async (state) => {
      if (state && (state.enEjecucion || state.finalizada)) {
        await hydrateEnvios(state)  // light state → fetch envios once
        wasSimRunningRef.current = true  // So external-cancel detection triggers correctly
        setBackendState(state)
        // Determine ownership: the browser that started the simulation has the localStorage flag.
        // localStorage persists across reloads and all tabs in the same browser, but NOT
        // across different browsers/machines — perfect for the A/B scenario.
        // If the simulation is finished, everyone gets full access.
        const owned = localStorage.getItem('simOwner') === '1'
        setIsOwner(owned || Boolean(state.finalizada))
        // Restore horaInicio so day-1 flight filter works correctly
        simStartMinuteRef.current = state.horaInicioMin || 0
        // Estimate current simulated minute from the server's day-start anchor —
        // works for a fresh browser/machine joining mid-simulation (B), unlike
        // localStorage which is only visible to the browser that started it (A).
        if (state.diaInicioTimestampUtc && state.enEjecucion && !state.finalizada) {
          const dayStartMinute = state.diaActual <= 1 ? (state.horaInicioMin || 0) : 0
          const elapsedSimMin  = ((Date.now() - state.diaInicioTimestampUtc) / 1000) * SIM_MIN_PER_REAL_SEC
          const isLastDay = (state.diaActual || 1) >= (state.totalDias || state.totalDays || 5)
          const ceiling = isLastDay ? (1440 + (state.horaInicioMin || 0)) : 1439
          const estimated = Math.min(dayStartMinute + elapsedSimMin, ceiling)
          setSimClockMinutes(estimated)
        }
        if (state.finalizada) setScreen('resultados')
      }
      // Always poll — even if no simulation is running — so B detects
      // when A starts one and cannot accidentally start a second simulation.
      startPolling()
    }).catch(() => {
      startPolling()  // Start polling even on error
    })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startPolling])

  function refreshStaticAirports() {
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
  }

  useEffect(() => {
    refreshStaticAirports()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    return () => {
      stopPolling()
      stopLive()
      stopOps()
    }
  }, [stopPolling]) // eslint-disable-line react-hooks/exhaustive-deps

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
    if (backendState?.colapsoPunto && !colapsoPuntoAlertedRef.current) {
      colapsoPuntoAlertedRef.current = true
      setAutoStep(false)
      clearInterval(autoStepRef.current)
    }
  }, [backendState?.colapsoPunto])

  useEffect(() => {
    if (!autoStep) {
      clearInterval(autoStepRef.current)
      return
    }

    function startTick() {
      autoStepRef.current = setInterval(() => {
        if (stepInProgressRef.current) return
        setSimClockMinutes((current) => Math.min(current + SIM_MINUTES_PER_REAL_SECOND, dayClockCapRef.current))
      }, 250)
    }

    startTick()

    // When tab goes to background, browsers throttle setInterval to ~1 Hz (4x slower).
    // Solution: pause the interval on hide, then on return advance the clock by the
    // full expected sim time that should have elapsed while hidden.
    let hiddenAt = null
    function onVisibilityChange() {
      if (document.hidden) {
        clearInterval(autoStepRef.current)
        hiddenAt = Date.now()
      } else if (hiddenAt !== null) {
        const hiddenSec = (Date.now() - hiddenAt) / 1000
        hiddenAt = null
        const missedMin = hiddenSec * SIM_MINUTES_PER_REAL_SECOND * 4
        setSimClockMinutes((current) => Math.min(current + missedMin, dayClockCapRef.current))
        startTick()
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      clearInterval(autoStepRef.current)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [autoStep])

  // At midnight: reset clock immediately (no freeze) and fire /step in background.
  // Animation resumes for the next day while /step processes. When response arrives,
  // setBackendState swaps in new day data. Brief window (~1-4s) of prior-day flight
  // data is acceptable and masked by fine-grained ticks.
  // Every day animates the full 24h (0→1440) so that N días covers N×24h. The LAST day
  // additionally animates the horaInicio-minutes tail past midnight (→ dayClockCapRef =
  // 1440 + simStartMinute) BEFORE firing the final /step, so the clock lands on día N+1
  // horaInicio (e.g. día 6 08:00 = 120h) instead of midnight (día 6 00:00 = 112h). The
  // backend finalises on this step regardless of clock (diaActual ≥ diasSimulacion).
  useEffect(() => {
    if (!autoStep) return
    const isLastDay = !!backendState
      && (backendState.diaActual || 1) >= (backendState.totalDias || backendState.totalDays || 5)
    const dayEndMin = 1440 + (isLastDay ? (simStartMinuteRef.current || 0) : 0)
    if (simClockMinutes < dayEndMin) return
    if (stepInProgressRef.current) return  // already fired this step

    stepInProgressRef.current = true

    const doStop = isLastDay
      ? api.stopSimulation().then((newState) => {
          if (!newState) return null
          hydrateEnvios(newState)
          setBackendState(newState)
          setAutoStep(false)
          clearInterval(autoStepRef.current)
          stopPolling()
          setScreen('resultados')
          return newState
        })
      : api.stepSimulation().then((newState) => {
          if (!newState) return null
          hydrateEnvios(newState)  // full response → cache envios for subsequent light polls
          setBackendState(newState)
          if (newState.finalizada) {
            setAutoStep(false)
            clearInterval(autoStepRef.current)
            stopPolling()
            setScreen('resultados')
          } else {
            // Reset clock to midnight simultaneously with new day data ONLY if continuing
            setSimClockMinutes(0)
          }
          return newState
        })

    doStop.catch((err) => {
      console.error('Auto-step error:', err)
    }).finally(() => {
      stepInProgressRef.current = false
    })
  }, [simClockMinutes, autoStep, backendState?.diaActual, backendState?.totalDias, backendState?.totalDays])




  const displayState = (backendState?.finalizada && prevSimStateRef.current) ? prevSimStateRef.current : backendState;

  // The envíos-table state (PLANIFICADO / EN_TRANSITO / ENTREGADO) only flips at flight
  // departure/arrival boundaries — it doesn't need the 250ms map-animation resolution.
  // Quantize the clock fed to the ~21k-envío remap so it recomputes ~every 1.25s instead
  // of 4×/s. The map's flight/occupancy animation still uses the fine simClockMinutes.
  const ENVIO_CLOCK_QUANTUM = 5
  const simClockForEnvios = Math.floor(simClockMinutes / ENVIO_CLOCK_QUANTUM) * ENVIO_CLOCK_QUANTUM

  const clockedEnviosForState = useMemo(() => {
    const rawEnvios = displayState?.envios || []

    // currentSimTime represents the exact UTC-equivalent instant in the simulation, shared by
    // every envío below — hoisted out of the old per-envío recompute.
    // simClockForEnvios is minutes-since-MIDNIGHT (día 1 starts at 480 = 08:00), but
    // fechaSimulada already carries horaInicio on día 1 (08:00). Adding them double-counts
    // horaInicio → currentSimTime landed ~8h ahead → false ENTREGADO. Mirror the visible
    // clock (fechaSimuladaDisplay): reset base to midnight so + simClockForEnvios is exact.
    const baseDate = displayState?.fechaSimulada ? new Date(displayState.fechaSimulada) : null
    if (baseDate) baseDate.setHours(0, 0, 0, 0)
    const currentSimTime = baseDate ? new Date(baseDate.getTime() + simClockForEnvios * 60000) : null

    // An envío shouldn't appear in any state (not even PENDIENTE) before the simulation clock
    // reaches its fechaHoraIngreso — otherwise shipments that "arrive" later today are visible
    // hours ahead of time, across every estado.
    const yaIngreso = (envio) => !currentSimTime || !envio.fechaHoraIngreso || new Date(envio.fechaHoraIngreso) <= currentSimTime

    // Backend plans every Sc block upfront on día 1 (see SimulationEngine.planificarSiguienteBloque),
    // so a shipment's route can exist in memory long before its own Sc window would have closed in
    // real time. Mirrors SimulationEngine.scWindowEnd(): a shipment only "reveals" as Planificado once
    // the Sc window it was ingested in has closed — before that it must still read as Pendiente,
    // exactly like the already-shipped Sc-gated reveal used for the almacén detail endpoint.
    const origenMs = displayState?.origenSimulacionUtc ? new Date(displayState.origenSimulacionUtc).getTime() : null
    const scMs = (displayState?.scMinutos || 0) * 60000
    const scWindowStillOpen = (envio) => {
      if (!origenMs || !scMs || !currentSimTime || !envio.fechaHoraIngreso) return false
      const ingresoMs = new Date(envio.fechaHoraIngreso).getTime()
      const minutosDesdeOrigen = Math.max(0, ingresoMs - origenMs)
      const ventana = Math.floor(minutosDesdeOrigen / scMs)
      const windowEndMs = origenMs + (ventana + 1) * scMs
      return currentSimTime.getTime() < windowEndMs
    }

    // clock defaults to currentSimTime; for ENTREGADO we pass the delivery instant so UT
    // freezes at delivery — otherwise the advancing clock eventually crosses the deadline
    // and a shipment delivered on time falsely reads "vencido".
    const withUt = (envio, overrides, clock = currentSimTime) =>
      ({ ...envio, ...overrides, tiempoRestante: computeTiempoRestante(envio, clock) })

    if (!displayState?.vuelos) return rawEnvios.filter(yaIngreso).map((envio) => withUt(envio))

    return rawEnvios.filter(yaIngreso).map((envio, index) => {
      const bEstado = envio.estado?.toUpperCase()
      if (['RETRASADO', 'CANCELADO'].includes(bEstado)) return withUt(envio)

      const vAsig = (envio.vuelosAsignados || []).filter(v => v != null)
      if (vAsig.length === 0) return withUt(envio)

      if (scWindowStillOpen(envio)) return withUt(envio, { estado: 'PENDIENTE' })

      if (envio.fechaSalidaPrimerVuelo && envio.fechaLlegadaUltimoVuelo && currentSimTime) {
        const firstDepartureTime = new Date(envio.fechaSalidaPrimerVuelo)
        const lastArrivalTime = new Date(envio.fechaLlegadaUltimoVuelo)

        // firstDepartureTime / lastArrivalTime are absolute datetimes, so a simple time
        // comparison works across day boundaries. The previous guard `firstDepartureTime >=
        // simStartTime` compared against the CURRENT day's start, which wrongly reverted any
        // envío that had departed on an earlier day back to PLANIFICADO — so multi-day
        // in-flight envíos never showed as EN_TRANSITO.
        const hasDeparted = currentSimTime >= firstDepartureTime
        // Mirror WarehouseOccupationCalculator: a bag keeps occupying the destination
        // warehouse for minutosRecogidaDestino after landing, until it's "picked up" —
        // so the shipment shouldn't flip to ENTREGADO in the list before that either.
        const recogidaMs = (displayState?.minutosRecogidaDestino || 0) * 60000
        const hasArrived = currentSimTime >= new Date(lastArrivalTime.getTime() + recogidaMs)

        if (hasArrived) {
            const deliveredAt = new Date(lastArrivalTime.getTime() + recogidaMs)
            return withUt(envio, { estado: 'ENTREGADO' }, deliveredAt)
        }

        if (hasDeparted) {
            return withUt(envio, { estado: 'EN_TRANSITO' })
        }

        return withUt(envio, { estado: 'PLANIFICADO' })
      }
      return withUt(envio)
    })
  }, [displayState?.envios, displayState?.vuelos, displayState?.fechaSimulada, displayState?.origenSimulacionUtc, displayState?.scMinutos, displayState?.minutosRecogidaDestino, simClockForEnvios])

  const simState = useMemo(() => {
    if (!displayState) return {
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
    return { ...displayState, envios: clockedEnviosForState }
  }, [displayState, clockedEnviosForState, staticAirports])

  const normalizedAirports = useMemo(() => {
    const airports = simState?.aeropuertos || simState?.airports || []
    const vuelosList = simState?.vuelos || []
    // When no simulation is running, force a neutral colour ('azul') so the map doesn't
    // show green markers — the backend returns semaforo='verde' for 0% occupancy which is
    // misleading when there is no active simulation.
    const hasActiveSim = Boolean(displayState)
    return airports.map((airport) => {
      const iata = airport.codigoIATA || airport.id
      const ocupFin = airport.currentOccupation ?? airport.ocupacionActual ?? 0
      const ocupIni = airport.ocupacionInicioDia ?? ocupFin
      return {
        ...airport,
        id: iata,
        name: airport.name || airport.nombre,
        continent: airport.continent || airport.continente,
        lat: airport.lat,
        lng: airport.lng,
        // Override occupation and flights to 0 if simulation is cancelled to bypass stale backend state
        currentOccupation: hasActiveSim ? ocupFin : 0,
        ocupacionInicioDia: hasActiveSim ? ocupIni : 0,
        eventosOcupacionDia: hasActiveSim ? (airport.eventosOcupacionDia ?? []) : [],
        warehouseCapacity: airport.warehouseCapacity ?? airport.capacidadAlmacen ?? 600,
        semaforo: hasActiveSim ? (airport.semaforo || 'verde') : 'azul',
        vuelosSalientes: hasActiveSim ? vuelosList.filter((v) => (v.origen || v.origin) === iata && v.estado === 'activo').length : 0,
        vuelosLlegando:  hasActiveSim ? vuelosList.filter((v) => (v.destino || v.destination) === iata && v.estado === 'activo').length : 0,
      }
    })
  }, [simState?.aeropuertos, simState?.airports, simState?.vuelos, displayState])

  const activeVuelosWithTimes = useMemo(() => {
    if (!displayState?.vuelos) return []
    return displayState.vuelos
      .filter((v) => v.estado === 'activo')
      .map((v) => ({
        id: v.codigoVuelo,
        origin: v.origen,
        destination: v.destino,
        currentLoad: v.maletasAsignadas ?? v.cargaActual ?? 0,
        capacity: v.capacidadTotal ?? 300,
        type: v.tipo === 'continental' ? 'continental' : 'intercontinental',
        status: 'active',
        horaSalida: v.horaSalida,
        horaLlegada: v.horaLlegada,
        husOrigen: v.husOrigen ?? null,
        husDestino: v.husDestino ?? null,
        depMin: parseTimeToMinutes(v.horaSalida),
        arrMin: parseTimeToMinutes(v.horaLlegada),
        cancelacionProgramada: v.cancelacionProgramada ?? false,
      }))
  }, [displayState?.vuelos])

  const originSet = useMemo(() => originIds ? new Set(originIds) : null, [originIds])
  const destSet = useMemo(() => destIds ? new Set(destIds) : null, [destIds])


  const clockedAirports = useMemo(() => {
    if (!backendState?.enEjecucion) return normalizedAirports

    const nextDep = {}
    const nextArr = {}
    const depFlightsConsidered = {}
    const arrFlightsConsidered = {}
    
    // In App.jsx simulation, backendState.diaActual gives the current simulation day (1-indexed)
    const isDayOne = (backendState?.diaActual || 1) === 1
    const simStartMinute = simStartMinuteRef.current

    activeVuelosWithTimes.forEach(v => {
      // Map filters
      if (originSet && !originSet.has(v.origin)) return
      if (destSet && !destSet.has(v.destination)) return

      let waitDep = null
      let waitArr = null

      if (v.depMin != null) {
        const utcDepMin = v.depMin // Ya viene en UTC, no aplicar fórmula
        let excludeDep = false
        if (isDayOne && utcDepMin < simStartMinute) {
          excludeDep = true
        }
        if (isDayOne && v.arrMin != null && utcDepMin > v.arrMin) {
          excludeDep = true
        }
        if (!excludeDep) {
          waitDep = Math.floor((utcDepMin - simClockMinutes + 1440) % 1440)
          if (!depFlightsConsidered[v.origin]) depFlightsConsidered[v.origin] = []
          depFlightsConsidered[v.origin].push({ id: v.id, time: v.horaSalida, wait: waitDep })
          if (nextDep[v.origin] === undefined || waitDep < nextDep[v.origin]) {
             nextDep[v.origin] = waitDep
          }
        }
      }
      
      if (v.arrMin != null) {
        const utcArrMin = v.arrMin // Ya viene en UTC, no aplicar fórmula
        const utcDepMin = v.depMin // Ya viene en UTC, no aplicar fórmula
        
        let excludeArr = false
        if (utcDepMin != null) {
          if (isDayOne && utcDepMin < utcArrMin && utcArrMin < simStartMinute) {
            excludeArr = true
          }
          if (isDayOne && utcDepMin > utcArrMin) {
            excludeArr = true
          }
        }
        
        if (!excludeArr) {
          if (utcDepMin != null && isActiveAtMinute(simClockMinutes, utcDepMin, utcArrMin)) {
            waitArr = Math.floor((utcArrMin - simClockMinutes + 1440) % 1440)
            if (!arrFlightsConsidered[v.destination]) arrFlightsConsidered[v.destination] = []
            arrFlightsConsidered[v.destination].push({ id: v.id, time: v.horaLlegada, wait: waitArr })
            if (nextArr[v.destination] === undefined || waitArr < nextArr[v.destination]) {
               nextArr[v.destination] = waitArr
            }
          }
        }
      }
    })

    return normalizedAirports.map((ap) => ({
      ...ap,
      currentOccupation: evalOccupancyAtMinute(ap.ocupacionInicioDia, ap.eventosOcupacionDia, simClockMinutes),
      nextDepartureWait: nextDep[ap.id] ?? Infinity,
      nextArrivalWait: nextArr[ap.id] ?? Infinity,
      debugDep: depFlightsConsidered[ap.id] || [],
      debugArr: arrFlightsConsidered[ap.id] || [],
    }))
  }, [normalizedAirports, simClockMinutes, backendState?.enEjecucion, activeVuelosWithTimes, originSet, destSet, backendState?.diaActual])

  const normalizedFlights = useMemo(() =>
    simState?.vuelos
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
      : (simState?.flights || []),
  [simState?.vuelos, simState?.flights])



  const visibleAirports = useMemo(() => {
    if (!originSet && !destSet) return clockedAirports
    const visible = new Set()
    for (const ap of clockedAirports) {
      if (!originSet || originSet.has(ap.id)) visible.add(ap.id)
      if (!destSet || destSet.has(ap.id)) visible.add(ap.id)
    }
    return clockedAirports.filter((a) => visible.has(a.id))
  }, [clockedAirports, originSet, destSet])

  const mapFilteredAirports = useMemo(() => {
    const { continent, pattern, semaforo } = airportMapFilter
    if (!continent && !pattern && semaforo.length === 0) return visibleAirports
    return visibleAirports.filter(a => {
      if (continent && (a.continent || a.continente || '') !== continent) return false
      if (pattern) {
        const pat = pattern.toLowerCase()
        if (!(a.id || '').toLowerCase().includes(pat)) return false
      }
      if (semaforo.length > 0) {
        const occ = a.currentOccupation ?? 0
        const cap = a.warehouseCapacity ?? 600
        const pct = cap > 0 ? (occ / cap) * 100 : 0
        const s = pct === 0 ? 'vacio' : pct >= threshold ? 'rojo' : pct >= threshold - 20 ? 'ambar' : 'verde'
        if (!semaforo.includes(s)) return false
      }
      return true
    })
  }, [visibleAirports, airportMapFilter, threshold])

  const normalizedRoutes = useMemo(() =>
    simState?.envios
      ? simState.envios.map((envio, idx) => ({
        id: envio.idEnvio || `RT-${idx}`,
        status: envio.estado === 'RETRASADO' ? 'red' : envio.estado === 'ENTREGADO' ? 'green' : 'amber',
        replanified: false,
        bags: envio.cantidadMaletas || 0,
        type: Number(envio.sla || 1) > 1 ? 'inter' : 'same',
        flightLegs: [{ origin: envio.aeropuertoOrigen, destination: envio.aeropuertoDestino }],
        etaRemaining: 0,
      }))
      : (simState?.routes || []),
  [simState?.envios, simState?.routes])



  // Light work: apply clock position. Reruns every second but only on pre-filtered list.
  // On Day 1, only flights departing at or after horaInicio are visible (no pre-existing
  // flights that were already in the air before the simulation started).
  const backendFlights = useMemo(() => {
    const day = displayState?.diaActual || displayState?.currentDay || 1
    const startMin = day <= 1 ? simStartMinuteRef.current : 0
    return activeVuelosWithTimes
      .filter((v) =>
        v.depMin >= startMin &&
        isActiveAtMinute(simClockMinutes, v.depMin, v.arrMin, day) &&
        (!originSet || originSet.has(v.origin)) &&
        (!destSet || destSet.has(v.destination))
      )
      .map((v) => ({
        ...v,
        fraction: flightFractionAtMinute(simClockMinutes, v.depMin, v.arrMin),
      }))
  }, [activeVuelosWithTimes, simClockMinutes, originSet, destSet, displayState?.diaActual])

  const mapFilteredFlights = useMemo(() => {
    const { origin, dest, semaforo, query } = vueloMapFilter
    if (!origin && !dest && semaforo.length === 0 && !query) return backendFlights
    return backendFlights.filter(f => {
      if (origin && f.origin !== origin) return false
      if (dest && f.destination !== dest) return false
      if (semaforo.length > 0) {
        const pct = f.capacity > 0 ? (f.currentLoad / f.capacity) * 100 : 0
        const s = pct === 0 ? 'vacio' : pct >= 60 ? (pct >= 85 ? 'rojo' : 'ambar') : 'verde'
        if (!semaforo.includes(s)) return false
      }
      if (query && !(f.id?.toLowerCase().includes(query) || f.origin?.toLowerCase().includes(query) || f.destination?.toLowerCase().includes(query))) return false
      return true
    })
  }, [backendFlights, vueloMapFilter])

  const backendPlannedFlights = useMemo(() => {
    const day = displayState?.diaActual || displayState?.currentDay || 1
    const startMin = day <= 1 ? simStartMinuteRef.current : 0
    return activeVuelosWithTimes
      .filter((v) =>
        v.depMin >= startMin &&
        v.depMin > simClockMinutes &&
        !isActiveAtMinute(simClockMinutes, v.depMin, v.arrMin, day) &&
        (!originSet || originSet.has(v.origin)) &&
        (!destSet || destSet.has(v.destination))
      )
      .map((v) => ({
        ...v,
        status: 'planned',
        fraction: 0,
      }))
  }, [activeVuelosWithTimes, simClockMinutes, originSet, destSet, displayState?.diaActual])

  const backendCancelledFlights = useMemo(() => {
    const vuelosMap = new Map((displayState?.vuelos || []).map(v => [v.codigoVuelo, v]))
    
    const cancelled = (displayState?.cancelaciones || [])
      .filter((c) => {
        const v = vuelosMap.get(c.codigoVuelo)
        return (!originSet || originSet.has(v?.origen)) && (!destSet || destSet.has(v?.destino))
      })
      .map(c => {
        const v = vuelosMap.get(c.codigoVuelo)
        return {
          id: c.codigoVuelo,
          uid: c.id,
          origin: v?.origen || '?',
          destination: v?.destino || '?',
          type: v?.tipo,
          status: 'cancelled',
          capacity: v?.capacidadTotal ?? 0,
          currentLoad: c.maletasAfectadas ?? 0,
          fecha: c.fecha,
          hora: c.hora,
          horaSalida: v?.horaSalida,
          horaLlegada: v?.horaLlegada,
          motivo: c.motivo,
          isCancelled: true
        }
      }).reverse()

    const scheduled = (displayState?.vuelos || [])
      .filter(v => v.cancelacionProgramada)
      .filter(v => (!originSet || originSet.has(v.origen)) && (!destSet || destSet.has(v.destino)))
      .map(v => ({
        id: v.codigoVuelo,
        uid: `sched-${v.codigoVuelo}`,
        origin: v.origen || '?',
        destination: v.destino || '?',
        type: v.tipo,
        status: 'cancelled',
        capacity: v.capacidadTotal ?? 0,
        currentLoad: 0,
        fecha: 'MAÑANA',
        hora: v.horaSalida,
        horaSalida: v.horaSalida,
        horaLlegada: v.horaLlegada,
        motivo: 'Cancelación Programada',
        isCancelled: true,
        isProgramada: true
      }))

    return [...scheduled, ...cancelled]
  }, [displayState?.cancelaciones, displayState?.vuelos, originSet, destSet])

  const fechaSimuladaDisplay = useMemo(() => {
    if (!displayState?.fechaSimulada) return null
    const source = new Date(displayState.fechaSimulada)
    if (Number.isNaN(source.getTime())) return displayState.fechaSimulada

    source.setHours(0, 0, 0, 0)
    const dayOffset = Math.max(0, ((displayState.diaActual || displayState.currentDay || 1) - 1)) * 24 * 60 * 60 * 1000
    const current = new Date(source.getTime() + dayOffset + simClockMinutes * 60000)
    const mm = String(current.getMonth() + 1).padStart(2, '0')
    const dd = String(current.getDate()).padStart(2, '0')
    const hh = String(current.getHours()).padStart(2, '0')
    const mi = String(current.getMinutes()).padStart(2, '0')
    return `${mm}-${dd} ${hh}:${mi}`
  }, [displayState?.fechaSimulada, displayState?.diaActual, displayState?.currentDay, simClockMinutes])

  // Calendar day only (no time-of-day) — used to date individual flight departures/arrivals.
  const simCurrentDate = useMemo(() => {
    if (!displayState?.fechaSimulada) return null
    const source = new Date(displayState.fechaSimulada)
    if (Number.isNaN(source.getTime())) return null
    source.setHours(0, 0, 0, 0)
    const dayOffset = Math.max(0, ((displayState.diaActual || displayState.currentDay || 1) - 1)) * 24 * 60 * 60 * 1000
    return new Date(source.getTime() + dayOffset)
  }, [displayState?.fechaSimulada, displayState?.diaActual, displayState?.currentDay])

  useEffect(() => {
    if (!selectedFlight) {
      setMapSelectedVuelo(null)
      return
    }
    const vuelo = backendFlights.find((f) => f.id === selectedFlight)
      || backendPlannedFlights.find((f) => f.id === selectedFlight)
      || backendCancelledFlights.find((f) => f.id === selectedFlight)
      
    if (vuelo) setMapSelectedVuelo(vuelo)
    // If vuelo not found in current frame, keep the previous drawer content open.
  }, [selectedFlight, displayState, backendFlights, backendPlannedFlights, backendCancelledFlights])

  const activeKpis = useMemo(() => {
    const base = displayState?.kpis
      ? {
          bagsInTransit: displayState.kpis.maletasEnTransito,
          bagsDelivered: displayState.kpis.maletasEntregadas,
          slaCompliance: displayState.kpis.cumplimientoSLA,
          activeFlights: displayState.kpis.vuelosActivos,
          slaViolated: displayState.kpis.slaVencidos,
        }
      : simState?.kpis ?? {
          bagsInTransit: 0, bagsDelivered: 0,
          slaCompliance: 0, activeFlights: 0,
          slaViolated: 0,
        }
    // ponytail: derive both global occupancy KPIs from the SAME instantaneous per-airport /
    // per-flight data the map animates, not the backend's kpis.ocupacion* — those are a
    // day-aggregate (fixed per day, exceeds 100%) that contradicts the green warehouses.
    const withCap = clockedAirports.filter((a) => (a.warehouseCapacity ?? 0) > 0)
    const whOcc = withCap.reduce((acc, a) => acc + (a.currentOccupation || 0), 0)
    const whCap = withCap.reduce((acc, a) => acc + (a.warehouseCapacity || 0), 0)
    const globalWarehouseOccupancy = whCap > 0 ? (whOcc / whCap) * 100 : 0

    // Airborne flights only (unfiltered by panel origin/dest) → fleet occupancy is 0 until the
    // first flights are in the air (~first 2h), then rises live, matching the map.
    const day = displayState?.diaActual || displayState?.currentDay || 1
    const startMin = day <= 1 ? simStartMinuteRef.current : 0
    const airborne = activeVuelosWithTimes.filter((v) =>
      v.depMin >= startMin && isActiveAtMinute(simClockMinutes, v.depMin, v.arrMin, day))
    const fleetLoad = airborne.reduce((acc, f) => acc + (f.currentLoad || 0), 0)
    const fleetCap = airborne.reduce((acc, f) => acc + (f.capacity || 0), 0)
    const globalFleetOccupancy = fleetCap > 0 ? (fleetLoad / fleetCap) * 100 : 0

    const freeFleetSpace = airborne.reduce((acc, f) => acc + Math.max(0, (f.capacity || 0) - (f.currentLoad || 0)), 0)
    const freeWarehouseSpace = withCap.reduce((acc, a) => acc + Math.max(0, (a.warehouseCapacity || 0) - (a.currentOccupation || 0)), 0)

    return { ...base, globalFleetOccupancy, globalWarehouseOccupancy, freeFleetSpace, freeWarehouseSpace }
  }, [displayState?.kpis, simState?.kpis, displayState?.diaActual, activeVuelosWithTimes, simClockMinutes, clockedAirports])

  const isOpsActive = Boolean(opsState)

  const opsNowMinutes = useMemo(() => {
    const now = new Date()
    return now.getUTCHours() * 60 + now.getUTCMinutes()
  }, [opsState?.vuelos])

  const opsActiveFlights = useMemo(() => {
    if (!opsState?.vuelos) return []
    return opsState.vuelos
      .filter((v) => v.estado !== 'cancelado')
      .map((v) => {
        const depMin = parseTimeToMinutes(v.horaSalida)
        const arrMin = parseTimeToMinutes(v.horaLlegada)
        
        // Use the exact inFlight status computed timezone-accurately by the backend
        const inFlight = v.inFlight ?? false
        
        return {
          id: v.codigoVuelo,
          origin: v.origen,
          destination: v.destino,
          currentLoad: v.cargaActual,
          capacity: v.capacidadTotal,
          type: v.tipo === 'continental' ? 'continental' : 'intercontinental',
          status: inFlight ? 'activo' : 'planificado',
          horaSalida: v.horaSalida,
          horaLlegada: v.horaLlegada,
          husOrigen: v.husOrigen ?? null,
          husDestino: v.husDestino ?? null,
          depMin,
          arrMin,
        }
      })
  }, [opsState?.vuelos])

  const opsAsSimState = useMemo(() => {
    if (!opsState) return null
    
    const nextDep = {}
    const nextArr = {}
    opsActiveFlights.forEach(v => {
      let waitDep = (v.depMin - opsNowMinutes + 1440) % 1440
      if (nextDep[v.origin] === undefined || waitDep < nextDep[v.origin]) {
         nextDep[v.origin] = waitDep
      }
      
      let waitArr = (v.arrMin - opsNowMinutes + 1440) % 1440
      if (nextArr[v.destination] === undefined || waitArr < nextArr[v.destination]) {
         nextArr[v.destination] = waitArr
      }
    })

    const envios = opsEnvios.map((e) => ({
      idEnvio: e.idEnvio ?? e.idPedido,
      aeropuertoOrigen: e.aeropuertoOrigen ?? e.iataOrigen,
      aeropuertoDestino: e.aeropuertoDestino ?? e.iataDestino,
      codigoAerolinea: e.codigoAerolinea ?? '--',
      estado: deriveOpsEnvioEstado(e, new Date()),
      cantidadMaletas: e.cantidadMaletas,
      sla: e.sla,
      fechaHoraIngreso: e.fechaHoraIngreso,
      fechaSalidaPrimerVuelo: e.fechaSalidaPrimerVuelo,
      fechaLlegadaUltimoVuelo: e.fechaLlegadaUltimoVuelo,
      escalas: [],
      planResumen: e.planResumen ?? null,
      planDetalle: e.planDetalle ?? null,
    }))
    const vuelos = (opsState.vuelos || [])
      .filter((v) => v.estado !== 'cancelado')
      .map((v) => ({
      codigoVuelo: v.codigoVuelo,
      origen: v.origen,
      destino: v.destino,
      horaSalida: v.horaSalida,
      horaLlegada: v.horaLlegada,
      tipo: v.tipo,
      estado: v.enUso ? 'EN_VUELO' : 'PROGRAMADO',
      capacidadTotal: v.capacidadTotal,
      cargaActual: v.cargaActual,
      enUso: v.enUso,
    }))
    const kpisNorm = opsReporte ? {
      maletasEnTransito: opsReporte.enviosPendientes,
      maletasEntregadas: opsReporte.enviosEntregados,
      cumplimientoSLA: opsReporte.porcentajeCumplimientoSla,
      vuelosActivos: (opsState.vuelos || []).filter((v) => v.enUso).length,
      slaVencidos: opsReporte.enviosViolados,
      bagsInTransit: opsReporte.enviosPendientes,
      bagsDelivered: opsReporte.enviosEntregados,
      slaCompliance: opsReporte.porcentajeCumplimientoSla,
      activeFlights: (opsState.vuelos || []).filter((v) => v.enUso).length,
      slaViolated: opsReporte.enviosViolados,
    } : null
    const aeropuertos = (opsState.aeropuertos || []).map((a) => ({
      ...a,
      id: a.codigoIATA,
      ocupacionActual: a.maletasPendientes,
      nextDepartureWait: nextDep[a.codigoIATA] ?? Infinity,
      nextArrivalWait: nextArr[a.codigoIATA] ?? Infinity,
    }))
    return {
      aeropuertos,
      vuelos,
      envios,
      kpis: kpisNorm,
      finalizada: true,
      totalDias: null,
      logOperaciones: [],
      cancelaciones: [],
    }
  }, [opsState, opsEnvios, opsReporte])

  async function handleReset() {
    stepInProgressRef.current = false
    colapsoPuntoAlertedRef.current = false
    wasSimRunningRef.current = false
    stopPolling()
    pollingErrorsRef.current = 0
    setPollingError(null)
    sessionStorage.removeItem('simOwner')
    localStorage.removeItem('simOwner')
    localStorage.removeItem('simHoraInicio')
    localStorage.removeItem('simDayStartedAt')
    localStorage.removeItem('simDayStartMinute')
    setIsOwner(false)
    onReset()
    setBackendState(null)
    api.resetSimulation()
      .then(() => refreshStaticAirports())
      .catch((err) => console.error('Reset backend error:', err))
      .finally(() => startPolling())
  }

  async function handleRestart() {
    if (!backendState) return
    stepInProgressRef.current = false
    stopPolling()
    setAutoStep(false)
    clearInterval(autoStepRef.current)
    setSimClockMinutes(0)
    setIsRestarting(true)
    try {
      const state = await api.restartSimulation()
      if (state) {
        hydrateEnvios(state)  // full response → cache envios
        // Same as handleSimulationStarted: zero out day-aggregate KPIs to avoid a
        // brief flash of stale occupancy; the first poll corrects with nowMin.
        const patchedState = state?.kpis
          ? { ...state, kpis: { ...state.kpis, ocupacionAlmacenes: 0, ocupacionFlota: 0 } }
          : state
        setBackendState(patchedState)
        setScreen('main')
        startPolling()
      }
    } catch (err) {
      console.error('Restart backend error:', err)
    } finally {
      setIsRestarting(false)
    }
  }

  function toLocalISO(date) {
    const pad = (n) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  // Ops backend frames "now" and flight times in UTC; send a true UTC instant.
  function toUtcISO(date) {
    const pad = (n) => String(n).padStart(2, '0')
    return `${date.getUTCFullYear()}-${pad(date.getUTCMonth()+1)}-${pad(date.getUTCDate())}T${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`
  }

  function stopLive() {
    clearTimeout(livePollingRef.current)
    clearTimeout(liveApplyRef.current)
    livePollingRef.current = null
    liveApplyRef.current = null
    liveNextStateRef.current = null
    liveWindowStartRef.current = null
    setLiveState(null)
  }

  function scheduleLiveTimers() {
    clearTimeout(livePollingRef.current)
    clearTimeout(liveApplyRef.current)

    // 55-min prefetch
    livePollingRef.current = setTimeout(() => {
      const nextFrom = new Date(liveWindowStartRef.current.getTime() + 60 * 60 * 1000)
      getLiveState(toLocalISO(nextFrom))
        .then((state) => { liveNextStateRef.current = state })
        .catch((err) => console.error('Live prefetch error:', err))
    }, 55 * 60 * 1000)

    // 60-min apply
    liveApplyRef.current = setTimeout(() => {
      const nextWindowStart = new Date(liveWindowStartRef.current.getTime() + 60 * 60 * 1000)
      liveWindowStartRef.current = nextWindowStart
      if (liveNextStateRef.current) {
        setLiveState(liveNextStateRef.current)
      } else {
        getLiveState(toLocalISO(nextWindowStart)).then(setLiveState).catch(console.error)
      }
      liveNextStateRef.current = null
      scheduleLiveTimers()
    }, 60 * 60 * 1000)
  }

  function startLive() {
    stopLive()
    const now = new Date()
    liveWindowStartRef.current = now
    getLiveState(toLocalISO(now)).then(setLiveState).catch((err) => console.error('Live fetch error:', err))
    scheduleLiveTimers()
  }

  function stopOps() {
    clearInterval(opsPollingRef.current)
    clearInterval(opsOccRef.current)
    opsPollingRef.current = null
    opsOccRef.current = null
    setOpsState(null)
    setOpsEnvios([])
    setOpsReporte(null)
  }

  function refreshOpsViewData() {
    getOpsEnvios().then((data) => setOpsEnvios(data || [])).catch((err) => console.error('Ops envios error:', err))
    getOpsReporte().then(setOpsReporte).catch((err) => console.error('Ops reporte error:', err))
  }

  // Ops is a REAL-time view. Full state (incl. flights) is heavier, so poll it
  // slower; warehouse occupancy is cheap, so poll it fast for a live airport
  // view that tracks the clock and newly ingested bags.
  const OPS_POLL_MS = 10 * 1000
  const OPS_OCC_POLL_MS = 2 * 1000

  function startOps() {
    stopOps()
    refreshOps()
    refreshOpsViewData()
    refreshOccupancy()
    opsPollingRef.current = setInterval(refreshOps, OPS_POLL_MS)
    opsOccRef.current = setInterval(refreshOccupancy, OPS_OCC_POLL_MS)
  }

  function refreshOps() {
    const now = new Date()
    getOpsState(toUtcISO(now)).then(setOpsState).catch((err) => console.error('Ops refresh error:', err))
    refreshOpsViewData()
  }

  // Fast path: refresh only airport occupancy, merging into the existing state.
  function refreshOccupancy() {
    const now = new Date()
    getOpsOccupancy(toUtcISO(now))
      .then((aeropuertos) => setOpsState((prev) => (prev ? { ...prev, aeropuertos } : { aeropuertos, vuelos: [] })))
      .catch((err) => console.error('Ops occupancy error:', err))
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const handleNavigate = useCallback((next) => {
    setConfigOpen(false)
    if (next === 'live') {
      setScreen('live')
      startLive()
    } else if (next === 'ops' || (next === 'main' && isOpsActive)) {
      if (screen === 'live') stopLive()
      setScreen('ops')
      if (!isOpsActive) startOps()
    } else if (isOpsActive && next === 'envios') {
      // Envíos is a side-panel section, not a standalone screen — same as non-ops mode.
      // OpsScreen owns its side panel state internally, so hand it a fresh request object.
      refreshOpsViewData()
      setScreen('ops')
      setOpsOpenSectionRequest({ section: 'envios', ts: Date.now() })
    } else if (isOpsActive && (next === 'dashboard' || next === 'resultados')) {
      refreshOpsViewData()
      setScreen(next)
    } else if (!isOpsActive && next === 'envios') {
      if (screen === 'live') stopLive()
      setScreen('main')
      setActiveSideSection('envios')
    } else if (next === 'config') {
      if (backendState) {
        if (screen === 'live') stopLive()
        void handleReset()
        setActiveSideSection('config')
        return
      }
      if (screen === 'live') stopLive()
      setScreen('main')
      setActiveSideSection('config')
    } else {
      if (screen === 'live') stopLive()
      if (screen === 'ops') stopOps()
      setScreen(next)
    }
  }, [screen, isOpsActive])

  // mapSelectedAirport is a snapshot captured at click time; re-derive the live version from
  // clockedAirports each render so occupancy/clock keep advancing while the drawer is open.
  const liveSelectedAirport = mapSelectedAirport
    ? (clockedAirports.find((a) => a.id === mapSelectedAirport.id) || mapSelectedAirport)
    : null
  const handleCloseAirport = useCallback(() => setMapSelectedAirport(null), [])
  const handleCloseVuelo   = useCallback(() => { setMapSelectedVuelo(null); setSelectedFlight(null); setFlightSource(null) }, [])
  const selectFlightFromMap   = useCallback((id) => { setFlightSource('map'); setSelectedFlight(id); if (id) setMapSelectedAirport(null); }, [])
  const selectFlightFromPanel = useCallback((id) => { setFlightSource('panel'); setSelectedFlight(id); if (id) setMapSelectedAirport(null); }, [])

  // Turn backend RutaDTO[] into map-ready routes (legs with coords + escala detail) and the
  // bounding box that frames them all. Returns null when no leg has known airport coords.
  const buildRutasParaMapa = useCallback((rutas) => {
    const apMap = Object.fromEntries(clockedAirports.map((a) => [a.id, a]))
    const routes = (rutas || []).map((r) => ({
      version: r.version,
      escalas: (r.escalas || []).map((e) => {
        const o = apMap[e.aeropuertoOrigen], d = apMap[e.aeropuertoDestino]
        if (!o || !d) return null
        return {
          vuelo: e.codigoVuelo,
          origen: e.aeropuertoOrigen, destino: e.aeropuertoDestino,
          horaSalida: e.horaSalidaEst, horaLlegada: e.horaLlegadaEst,
          completada: e.completada,
          originLat: o.lat, originLng: o.lng, destLat: d.lat, destLng: d.lng,
        }
      }).filter(Boolean),
    })).filter((r) => r.escalas.length > 0)
    if (routes.length === 0) return null
    const lats = routes.flatMap((r) => r.escalas.flatMap((e) => [e.originLat, e.destLat]))
    const lngs = routes.flatMap((r) => r.escalas.flatMap((e) => [e.originLng, e.destLng]))
    return { routes, bounds: [[Math.min(...lats), Math.min(...lngs)], [Math.max(...lats), Math.max(...lngs)]] }
  }, [clockedAirports])

  // Show ALL routes an envío's bags take (a split envío has several) with their escala detail.
  const handleShowEnvioRoute = useCallback(async (envioId) => {
    try {
      let rutas = null
      try { rutas = await api.getEnvioRutas(envioId) } catch { rutas = null }
      // Fallback (e.g. ops mode has no /rutas endpoint): a single route from the envío detail.
      if (!rutas || rutas.length === 0) {
        const envio = await api.getEnvioById(envioId)
        const escalas = [...(envio?.planDetalle?.escalas || [])].sort((a, b) => a.orden - b.orden)
        let originIata = envio?.aeropuertoOrigen
        const det = escalas.map((esc) => {
          const leg = { codigoVuelo: esc.codigoVuelo, aeropuertoOrigen: originIata, aeropuertoDestino: esc.codigoAeropuerto, horaSalidaEst: esc.horaSalidaEst, horaLlegadaEst: esc.horaLlegadaEst }
          originIata = esc.codigoAeropuerto
          return leg
        })
        rutas = det.length ? [{ version: 1, escalas: det }] : []
      }
      const built = buildRutasParaMapa(rutas)
      if (!built) return
      setHighlightedRoute({ envioId, routes: built.routes })
      setScreen('main')
      setMapFlyTo({ bounds: built.bounds, duration: 1.0 })
    } catch (e) {
      console.error('handleShowEnvioRoute', e)
    }
  }, [buildRutasParaMapa])

  // Show the single route a specific maleta follows (its plan version).
  const handleShowMaletaRoute = useCallback(async (maletaId) => {
    try {
      const ruta = await api.getMaletaRuta(maletaId)
      const built = buildRutasParaMapa(ruta ? [ruta] : [])
      if (!built) return
      setHighlightedRoute({ maletaId, routes: built.routes })
      setScreen('main')
      setMapFlyTo({ bounds: built.bounds, duration: 1.0 })
    } catch (e) {
      console.error('handleShowMaletaRoute', e)
    }
  }, [buildRutasParaMapa])
  const handleCancelFlight = useCallback(async (codigoVuelo, aplicaDesde = 'HOY') => {
    try {
      // cancelFlight returns the fresh SimulationStateDTO with cancelaciones already included.
      // We use that response directly to avoid stale cachedState from getState().
      const newState = await api.cancelFlight(codigoVuelo, aplicaDesde)
      setMapSelectedVuelo(null)
      setSelectedFlight(null)
      if (newState && (newState.enEjecucion || newState.finalizada)) {
        hydrateEnvios(newState)  // full response → cache envios
        setBackendState(newState)
      }
    } catch (err) {
      alert('Error al cancelar vuelo: ' + (err instanceof Error ? err.message : String(err)))
    }
  }, [])
  // Ops reads flight cancellations from a separate mechanism than the main simulation
  // (see api.cancelLiveFlight) — it is not backed by SimulationEngine.
  const handleCancelOpsFlight = useCallback(async (codigoVuelo, aplicaDesde = 'HOY') => {
    try {
      await api.cancelOpsFlight(codigoVuelo, aplicaDesde)
      refreshOps()
    } catch (err) {
      alert('Error al cancelar vuelo: ' + (err instanceof Error ? err.message : String(err)))
    }
  }, [])

  const handleClearOpsCancellations = useCallback(async () => {
    try {
      await api.clearOpsCancellations()
      refreshOps()
    } catch (err) {
      alert('Error al limpiar cancelaciones: ' + (err instanceof Error ? err.message : String(err)))
    }
  }, [])
  const handleBackToMain   = useCallback(() => setScreen(isOpsActive ? 'ops' : 'main'), [isOpsActive])

  const handleCancelConfig = useCallback(() => {
    setScreen('main')
    setConfigOpen(false)
  }, [])

  const handleOpenOps = useCallback(() => {
    setActiveSideSection(null)
    handleNavigate('ops')
  }, [handleNavigate])

  const handleSimulationStarted = useCallback((state, params) => {
    setConfigOpen(false)
    sessionStorage.setItem('simOwner', '1')
    localStorage.setItem('simOwner', '1')
    setIsOwner(true)
    wasSimRunningRef.current = true
    hydrateEnvios(state)  // full /start response → seed the envios cache
    // The initial state's KPIs contain day-aggregate ocupacionAlmacenes (all day-1 bags
    // counted at once → ~73%), not the time-zero value (~0%). Patch them to 0 so the
    // first rendered frame doesn't flash a high occupancy. The next poll (within ~2s)
    // provides the correct time-projected values via getEstadoInstantaneo(nowMin).
    const patchedState = state?.kpis
      ? { ...state, kpis: { ...state.kpis, ocupacionAlmacenes: 0, ocupacionFlota: 0 } }
      : state
    setBackendState(patchedState)
    setLastParams(params)
    const [h = 0, m = 0] = (params?.horaInicio || '00:00').split(':').map(Number)
    const startMin = h * 60 + m
    simStartMinuteRef.current = startMin
    setSimClockMinutes(startMin)
    // Capture wall-clock start for real elapsed timer in FloatingClocks
    setSimStartedAt(Date.now())
    setScreen('main')
    setActiveSideSection(null)
    startPolling()
  }, [startPolling, hydrateEnvios])

  const mapContainerRef = useRef(null)
  const kpiWidgetRef = useRef(null)
  const clockWidgetRef = useRef(null)

  const handleShowWidgets = () => {
    if (kpiWidgetRef.current) {
      kpiWidgetRef.current.setVisibility(true)
      kpiWidgetRef.current.resetPosition()
    }
    if (clockWidgetRef.current) {
      clockWidgetRef.current.setVisibility(true)
      clockWidgetRef.current.resetPosition()
    }
    window.dispatchEvent(new CustomEvent('restoreWidgets'))
  }

  return (
    <>
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden', background: 'var(--bg)' }}>
      <TopBar
        simRateLabel={null}
        kpis={activeKpis}
        backendState={backendState}
        onCancel={handleReset}
        onRestart={handleRestart}
        canRestart={Boolean(backendState)}
        theme={theme}
        onToggleTheme={onToggleTheme}
        onNavigate={handleNavigate}
        onIniciar={onIniciar}
        screen={screen}
        hasSimulation={Boolean(backendState)}
        isOpsActive={isOpsActive}
        colapsoPunto={backendState?.colapsoPunto ?? null}
        liveActive={screen === 'live'}
        onShowWidgets={handleShowWidgets}
        isOwner={isOwner}
      />
      {backendState?.colapsoPunto && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0,
          height: 32, padding: '0 20px',
          background: 'rgba(240,75,75,0.12)',
          borderBottom: '1px solid rgba(240,75,75,0.35)',
          fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--red)',
        }}>
          <span style={{ fontWeight: 700 }}>⚠ COLAPSO DETECTADO</span>
          <span style={{ color: 'rgba(240,75,75,0.4)' }}>|</span>
          <span>DÍA {backendState.colapsoPunto.dia}</span>
          <span style={{ color: 'rgba(240,75,75,0.4)' }}>|</span>
          <span>{backendState.colapsoPunto.tipo === 'ALMACEN'
            ? `Ocupación: ${backendState.colapsoPunto.pctSlaVencido}%`
            : `Envíos con SLA vencido: ${backendState.colapsoPunto.enviosSlaVencidos ?? 0}`}</span>
          <span style={{ color: 'rgba(240,75,75,0.4)' }}>|</span>
          <span>Aeropuerto crítico: <strong>{backendState.colapsoPunto.aeropuertoMasCritico}</strong></span>
          <button
            onClick={() => handleNavigate('colapso')}
            style={{ marginLeft: 'auto', background: 'transparent', border: '1px solid rgba(240,75,75,0.4)', color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 10, padding: '2px 8px', borderRadius: 3, cursor: 'pointer', letterSpacing: 1 }}
          >
            VER REPORTE →
          </button>
        </div>
      )}
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative', minHeight: 0 }}>
        {/* ── OPERACIONES (main map view) ─────────────────────────────── */}
        {(screen === 'main' && !configOpen) && (
          <div style={{ position: 'relative', height: '100%', overflow: 'hidden' }}>
            <div ref={mapContainerRef} style={{
              position: 'absolute',
              top: 0,
              right: 0,
              bottom: 0,
              left: activeSideSection ? 412 : 52,
              zIndex: 0,
              transition: 'left 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
            }}>
              <MapView
                airports={mapFilteredAirports}
                flights={mapFilteredFlights}
                selectedFlight={selectedFlight}
                setSelectedFlight={selectFlightFromMap}
                selectedFlightData={mapSelectedVuelo}
                onAirportClick={(ap) => { setMapSelectedAirport(ap); handleCloseVuelo(); }}
                onMapClick={() => { handleCloseVuelo(); handleCloseAirport(); setHighlightedRoute(null) }}
                theme={theme}
                threshold={threshold}
                highlightedRoute={highlightedRoute}
                flyToTarget={mapFlyTo}
              />
            </div>

            {/* Side panel — overlay on top of map */}
            <div style={{ position: 'absolute', top: 0, left: 0, bottom: 0, zIndex: 700, display: 'flex' }}>
              <SidePanel
                activeSection={activeSideSection}
                onSectionChange={(section) => {
                  setActiveSideSection(section)
                  if (section) {
                    handleCloseAirport()
                    handleCloseVuelo()
                  }
                }}
                flights={backendFlights}
                plannedFlights={backendPlannedFlights}
                cancelledFlights={backendCancelledFlights}
                selectedFlight={selectedFlight}
                setSelectedFlight={selectFlightFromPanel}
                setMapSelectedVuelo={setMapSelectedVuelo}
                setMapSelectedAirport={(ap) => { setMapSelectedAirport(ap); handleCloseVuelo(); }}
                simState={simState}
                onShowEnvioRoute={handleShowEnvioRoute}
                onShowMaletaRoute={handleShowMaletaRoute}
                onClearRoute={() => setHighlightedRoute(null)}
                airports={clockedAirports}
                onVueloFilterChange={setVueloMapFilter}
                onAirportFilterChange={setAirportMapFilter}
                onFocusMapLocation={setMapFlyTo}
                threshold={threshold}
                setThreshold={setThreshold}
                onSimulationStarted={handleSimulationStarted}
                originIds={originIds}
                setOriginIds={setOriginIds}
                destIds={destIds}
                setDestIds={setDestIds}
                theme={theme}
                onOpenOps={handleOpenOps}
                isOwner={isOwner}
                hasSimulation={Boolean(backendState)}
                onClearCancellations={isOpsActive ? handleClearOpsCancellations : undefined}
                mode={isOpsActive ? 'ops' : 'simulacion'}
                nowMin={simClockMinutes}
              />
            </div>

            {/* KPIs / clocks — bottom-right */}
            <div style={{
              position: 'absolute', bottom: 20, right: 20,
              zIndex: 600,
              display: 'flex', flexDirection: 'column', gap: 10, pointerEvents: 'none',
            }}>
              <DraggableWidget ref={kpiWidgetRef} containerRef={mapContainerRef}>
                <FloatingKPIs kpis={activeKpis} hasSimulation={Boolean(backendState)} />
              </DraggableWidget>
              <DraggableWidget ref={clockWidgetRef} containerRef={mapContainerRef}>
                <FloatingClocks backendState={backendState} simClockMinutes={simClockMinutes} simStartMinute={simStartMinuteRef.current} simStartedAt={simStartedAt} esColapso={lastParams?.esColapso} />
              </DraggableWidget>
              {backendState && !backendState?.finalizada && (
                <button
                  onClick={() => setAutoStep((v) => !v)}
                  style={{
                    pointerEvents: 'auto', alignSelf: 'flex-end',
                    background: 'var(--panel)', border: '1px solid var(--border)',
                    color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: 12,
                    padding: '6px 12px', borderRadius: 6, cursor: 'pointer',
                  }}
                >
                  {autoStep ? '⏸ Pausar' : '▶ Reanudar'}
                </button>
              )}
            </div>

            {/* Flight cajetín — top-left, shifts right when side panel open */}
            {mapSelectedVuelo && flightSource === 'map' && (
              <div style={{
                position: 'absolute', top: 20,
                left: activeSideSection ? 432 : 72,
                zIndex: 600, pointerEvents: 'none',
                transition: 'left 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
              }}>
                <DraggableWidget containerRef={mapContainerRef} hideVisibilityToggle>
                  <FloatingFlightInfo
                    vuelo={mapSelectedVuelo}
                    onClose={handleCloseVuelo}
                    fetchEnvios={isOpsActive ? api.getOpsEnviosByFlight : api.getEnviosByFlight}
                    simDate={simCurrentDate}
                  />
                </DraggableWidget>
              </div>
            )}

            <DrawerAeropuerto
              airport={liveSelectedAirport}
              vuelos={backendState?.vuelos || []}
              onClose={handleCloseAirport}
              nowMinuteUtc={simClockMinutes}
              fetchInventory={isOpsActive ? api.getOpsAirportInventory : api.getAirportInventory}
              umbralVerde={threshold - 20}
              umbralRojo={threshold}
            />
            {flightSource === 'panel' && (
              <DrawerVuelo
                vuelo={mapSelectedVuelo}
                onClose={handleCloseVuelo}
                onCancelFlight={isOwner ? handleCancelFlight : null}
                fetchEnvios={isOpsActive ? api.getOpsEnviosByFlight : api.getEnviosByFlight}
                simClockMinutes={simClockMinutes}
              />
            )}
          </div>
        )}

        {/* ── LIVE VIEW — full height, own layout ── */}
        {screen === 'live' && (
          <div style={{ height: '100%', overflow: 'hidden' }}>
            <LiveScreen
              liveState={liveState}
              theme={theme}
              onBack={() => handleNavigate('main')}
            />
          </div>
        )}

        {/* ── OPS VIEW — full height, own layout ── */}
        {screen === 'ops' && (
          <div style={{ height: '100%', overflow: 'hidden' }}>
            <OpsScreen
              opsState={opsState}
              opsEnvios={opsEnvios}
              theme={theme}
              onBack={() => { stopOps(); handleNavigate('config') }}
              onRefreshOps={() => { refreshOps(); refreshOpsViewData() }}
              onCancelFlight={handleCancelOpsFlight}
              onClearCancellations={handleClearOpsCancellations}
              openSectionRequest={opsOpenSectionRequest}
            />
          </div>
        )}

        {/* ── OVERLAY SCREENS ── */}
        {screen !== 'main' && screen !== 'live' && screen !== 'ops' && (
          <div style={{ height: '100%', overflow: 'auto', background: 'var(--bg)' }}>
            {screen === 'dashboard' && (
              <DashboardScreen
                simState={isOpsActive ? opsAsSimState : simState}
                theme={theme}
                onBack={handleBackToMain}
                globalKpis={isOpsActive ? null : activeKpis}
                opsMode={isOpsActive}
              />
            )}
            {screen === 'resultados' && (
              <ResultadosScreen
                /* Use backendState (the real final/collapse snapshot), NOT simState — on
                   finalize/collapse simState falls back to prevSimStateRef (the pre-collapse
                   frame) which has finalizada=false and no colapsoPunto, so these screens
                   wrongly showed "SIN RESULTADOS" / "no se detectó colapso". */
                simState={isOpsActive ? opsAsSimState : (backendState || simState)}
                theme={theme}
                onBack={handleBackToMain}
                opsMode={isOpsActive}
              />
            )}
            {screen === 'colapso' && (
              <ColapsoScreen
                simState={backendState || simState}
                theme={theme}
                onBack={handleBackToMain}
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
    {pollingError && (
      <div style={{ position: 'fixed', bottom: 16, right: 16, zIndex: 1500, background: 'rgba(240,75,75,0.12)', border: '1px solid rgba(240,75,75,0.4)', borderRadius: 8, padding: '10px 16px', display: 'flex', alignItems: 'center', gap: 10, backdropFilter: 'blur(6px)' }}>
        <span style={{ color: '#f04b4b', fontSize: 14 }}>⚠</span>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color: '#f04b4b' }}>{pollingError}</span>
        <button onClick={() => setPollingError(null)} style={{ background: 'none', border: 'none', color: 'var(--muted)', cursor: 'pointer', fontSize: 14, lineHeight: 1, padding: 0, marginLeft: 4 }}>✕</button>
      </div>
    )}
    {isRestarting && (
      <div style={{ position: 'fixed', inset: 0, zIndex: 2000, background: 'rgba(13,17,23,0.88)', backdropFilter: 'blur(4px)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 24 }}>
        <div style={{ width: 52, height: 52, borderRadius: '50%', border: '3px solid rgba(88,166,255,0.15)', borderTopColor: 'var(--blue)', animation: 'spin 0.75s linear infinite' }} />
        <div style={{ textAlign: 'center' }}>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--text)', letterSpacing: 1, marginBottom: 6 }}>Reiniciando simulación…</div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)' }}>Reutilizando rutas planificadas</div>
        </div>
      </div>
    )}
  </>
  )
}
