import React, { useEffect, useRef, useState } from 'react'
import LeftPanel from '../components/LeftPanel.jsx'
import MapView from '../components/MapView.jsx'
import RightPanel from '../components/RightPanel.jsx'
import TopBar from '../components/TopBar.jsx'
import { api } from '../services/api.js'

export default function Simulacion() {
  const [simDay, setSimDay] = useState(1)
  const [simHour, setSimHour] = useState(6)
  const [simMin, setSimMin] = useState(0)
  const [simPeriod, setSimPeriod] = useState('weekly')
  const [running, setRunning] = useState(false)
  const [realElapsedSeconds, setRealElapsedSeconds] = useState(0)

  const [threshold, setThreshold] = useState(80)

  const [filters, setFilters] = useState({
    status: ['green', 'amber', 'red'],
    route: ['same', 'inter'],
  })

  const [selectedFlight, setSelectedFlight] = useState(null)
  const [selectedRoute, setSelectedRoute] = useState(null)
  const [staticAirports, setStaticAirports] = useState([])

  const intervalRef = useRef(null)
  const realStartRef = useRef(null)
  const accumulatedRealMsRef = useRef(0)
  const maxDay = simPeriod === '3day' ? 3 : 5

  function onToggleSim() {
    setRunning((current) => !current)
  }

  function onReset() {
    setRunning(false)
    setSimDay(1)
    setSimHour(6)
    setSimMin(0)
    realStartRef.current = null
    accumulatedRealMsRef.current = 0
    setRealElapsedSeconds(0)
    setSelectedFlight(null)
    setSelectedRoute(null)
  }

  useEffect(() => {
    if (running && realStartRef.current === null) {
      realStartRef.current = Date.now()
    }
    if (!running && realStartRef.current !== null) {
      accumulatedRealMsRef.current += Date.now() - realStartRef.current
      realStartRef.current = null
      setRealElapsedSeconds(Math.floor(accumulatedRealMsRef.current / 1000))
    }
  }, [running])

  useEffect(() => {
    if (!running) return undefined
    const id = setInterval(() => {
      const liveMs = accumulatedRealMsRef.current + (Date.now() - realStartRef.current)
      setRealElapsedSeconds(Math.floor(liveMs / 1000))
    }, 250)
    return () => clearInterval(id)
  }, [running])

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

  useEffect(() => {
    api.getAirports()
      .then((data) => setStaticAirports(
        data.map((airport) => ({
          ...airport,
          id: airport.codigoIATA,
          name: airport.nombre,
          currentOccupation: airport.ocupacionActual ?? 0,
          warehouseCapacity: airport.capacidadAlmacen ?? 600,
        }))
      ))
      .catch(() => {})
  }, [])

  const simState = {
    currentDay: 0,
    totalDays: 0,
    elapsedSeconds: 0,
    algorithm: 'SIMULATED_ANNEALING',
    kpis: {
      bagsInTransit: 0,
      bagsDelivered: 0,
      slaCompliance: 0,
      activeFlights: 0,
      slaViolated: 0,
    },
    airports: staticAirports,
    flights: [],
    routes: [],
    throughputHistory: [],
    logOperaciones: [],
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', background: 'var(--bg)' }}>
      <TopBar
        currentDay={simState.currentDay}
        totalDays={simState.totalDays}
        elapsedSeconds={simState.elapsedSeconds}
        realElapsedSeconds={realElapsedSeconds}
        kpis={simState.kpis}
        simPeriod={simPeriod}
        setSimPeriod={setSimPeriod}
        running={running}
        onToggleSim={onToggleSim}
        onReset={onReset}
      />
      <div style={{ display: 'grid', gridTemplateColumns: '188px 1fr 240px', flex: 1, overflow: 'hidden' }}>
        <LeftPanel filters={filters} setFilters={setFilters} threshold={threshold} setThreshold={setThreshold} />

        <div style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <MapView
            airports={simState.airports}
            routes={simState.routes}
            flights={simState.flights}
            filters={filters}
            threshold={threshold}
            simHour={simHour}
            simMin={simMin}
            selectedRoute={selectedRoute}
            setSelectedRoute={setSelectedRoute}
            selectedFlight={selectedFlight}
            setSelectedFlight={setSelectedFlight}
          />
        </div>

        <RightPanel
          flights={simState.flights}
          airports={simState.airports}
          threshold={threshold}
          selectedFlight={selectedFlight}
          setSelectedFlight={setSelectedFlight}
        />
      </div>
    </div>
  )
}
