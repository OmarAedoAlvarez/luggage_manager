import React, { useEffect, useMemo } from 'react'
import { MapContainer, TileLayer, CircleMarker, Polyline, Tooltip, Marker, useMap } from 'react-leaflet'
import L from 'leaflet'
import { getWarehouseStatus, STATUS_COLOR } from '../simulation/statusRules.js'

function FitBounds() {
  const map = useMap()
  useEffect(() => { map.setView([20, 10], 2) }, [map])
  return null
}

// Automatically invalidate map size whenever its container is resized
function MapResizer() {
  const map = useMap()
  useEffect(() => {
    const container = map.getContainer()
    const observer = new ResizeObserver(() => { map.invalidateSize() })
    observer.observe(container)
    map.invalidateSize()
    return () => observer.disconnect()
  }, [map])
  return null
}

const airportIndex = (airports) =>
  Object.fromEntries(airports.map((a) => [a.id, a]))

function warehouseStatus(ap, threshold) {
  const pct = (ap.currentOccupation / ap.warehouseCapacity) * 100
  return getWarehouseStatus(pct, threshold)
}

function occupancyPct(ap) {
  return Math.round((ap.currentOccupation / ap.warehouseCapacity) * 100)
}

// Linearly interpolate position along origin→destination
function lerpPos(originAp, destAp, fraction) {
  if (!originAp || !destAp) return null
  return [
    originAp.lat + (destAp.lat - originAp.lat) * fraction,
    originAp.lng + (destAp.lng - originAp.lng) * fraction,
  ]
}

function flightBearing(originAp, destAp) {
  if (!originAp || !destAp) return 0
  const lat1 = (originAp.lat * Math.PI) / 180
  const lat2 = (destAp.lat * Math.PI) / 180
  const dLng = ((destAp.lng - originAp.lng) * Math.PI) / 180

  const y = Math.sin(dLng) * Math.cos(lat2)
  const x =
    Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)

  const bearingFromNorth = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360
  // SVG points north (up), rotation = bearing from north directly.
  return bearingFromNorth
}

function makeDivIcon(selected, angle, theme) {
  const color = selected
    ? (theme === 'light' ? '#0553b1' : '#74b3ff')
    : (theme === 'light' ? '#0969da' : '#4d9fff')
  const shadow = selected ? `drop-shadow(0 0 4px ${color})` : 'none'
  return L.divIcon({
    className: '',
    html: `<div class="flight-plane${selected ? ' flight-plane-selected' : ''}" style="transform:rotate(${angle}deg);filter:${shadow};transition:filter 0.2s"><svg viewBox="0 0 24 24" width="18" height="18" xmlns="http://www.w3.org/2000/svg"><path fill="${color}" d="M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"/></svg></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  })
}

export default function MapView({
  airports, routes, flights,
  filters, threshold,
  selectedRoute,  setSelectedRoute,
  selectedFlight, setSelectedFlight,
  onAirportClick,
  onFlightFromRoute,
  theme = 'dark',
}) {
  const airportList = airports || []
  const routeList = routes || []
  const flightList = flights || []

  const apIdx = useMemo(() => airportIndex(airportList), [airportList])
  // Index flights by origin->destination for quick lookup from route clicks
  const flightByLeg = useMemo(() => {
    const idx = {}
    flightList.forEach((f) => { idx[`${f.origin}->${f.destination}`] = f })
    return idx
  }, [flightList])

  // Filter routes based on status + type toggles
  const visibleRoutes = routeList.filter((r) => {
    if (!filters.status.includes(r.status)) return false
    if (!filters.route.includes(r.type))    return false
    return true
  })

  // Only show active (non-cancelled) flights on map
  const activeFlights = flightList.filter((f) => f.status === 'active')

  return (
    <MapContainer
      center={[20, 10]} zoom={2}
      style={{ width: '100%', height: '100%', background: '#090e19' }}
      zoomControl={false} attributionControl={false}
    >
      <FitBounds />
      <MapResizer />
      <TileLayer
        url={theme === 'light'
          ? 'https://{s}.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}{r}.png'
          : 'https://{s}.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}{r}.png'}
        subdomains="abcd" maxZoom={19}
      />

      {/* ── ROUTE LINES ───────────────────────────────────────────────────── */}
      {visibleRoutes.map((route) => {
        const legs = route.flightLegs
        const isSelected = selectedRoute?.id === route.id
        const color = STATUS_COLOR[route.status]

        return legs.map((leg, li) => {
          const a = apIdx[leg.origin], b = apIdx[leg.destination]
          if (!a || !b) return null
          return (
            <Polyline
              key={`${route.id}-${li}`}
              positions={[[a.lat, a.lng], [b.lat, b.lng]]}
              pathOptions={{
                color,
                weight:    isSelected ? 2.5 : 1.4,
                opacity:   isSelected ? 1   : route.status === 'green' ? (theme === 'light' ? 0.6 : 0.45) : 0.75,
                dashArray: route.replanified ? '7,5' : undefined,
              }}
              eventHandlers={{
                click: () => {
                  setSelectedRoute(isSelected ? null : route)
                  const flight = flightByLeg[`${leg.origin}->${leg.destination}`]
                  if (flight && onFlightFromRoute) {
                    setSelectedFlight(flight.id)
                    onFlightFromRoute(flight)
                  }
                },
              }}
            >
              <Tooltip className="tasf-tooltip" sticky>
                <strong style={{ color }}>{route.id}</strong> — {leg.origin} → {leg.destination}<br />
                {route.bags} maletas · {route.type === 'same' ? 'Continental' : 'Intercontinental'}<br />
                {route.replanified && <span style={{ color: '#f5a623' }}>↺ Replanificado</span>}
                {!route.replanified && route.status === 'green' && <span style={{ color: '#22d07a' }}>✓ En ruta</span>}
                {route.status === 'amber' && <span style={{ color: '#f5a623' }}>⚠ Conexión interrumpida</span>}
                {route.status === 'red'   && <span style={{ color: '#f04b4b' }}>✕ SLA vencido</span>}
              </Tooltip>
            </Polyline>
          )
        })
      })}

      {/* ── ACTIVE FLIGHT PATHS (clickable) ──────────────────────────────── */}
      {activeFlights.map((flight) => {
        const a = apIdx[flight.origin], b = apIdx[flight.destination]
        if (!a || !b) return null
        const isSelected = selectedFlight === flight.id
        return (
          <Polyline
            key={`fp-${flight.id}`}
            positions={[[a.lat, a.lng], [b.lat, b.lng]]}
            pathOptions={{
              color: isSelected ? '#3d8bff' : '#f5a623',
              weight: isSelected ? 6 : 4,
              opacity: isSelected ? 0.75 : 0.35,
              dashArray: '3,6',
            }}
            eventHandlers={{
              click: () => setSelectedFlight(isSelected ? null : flight.id),
            }}
          >
            <Tooltip className="tasf-tooltip" direction="top" sticky>
              <strong style={{ color: '#3d8bff' }}>{flight.id}</strong><br />
              {flight.origin} → {flight.destination}<br />
              {flight.currentLoad}/{flight.capacity} maletas<br />
              {flight.type === 'continental' ? 'Continental' : 'Intercontinental'}
            </Tooltip>
          </Polyline>
        )
      })}

      {/* ── ANIMATED FLIGHT MARKERS (visual only, no click) ──────────────── */}
      {activeFlights.map((flight) => {
        const a = apIdx[flight.origin], b = apIdx[flight.destination]
        const pos = lerpPos(a, b, flight.fraction)
        if (!pos) return null
        const isSelected = selectedFlight === flight.id
        const icon = makeDivIcon(isSelected, flightBearing(a, b), theme)
        return (
          <Marker
            key={`fm-${flight.id}-${isSelected ? 'sel' : 'norm'}`}
            position={pos}
            icon={icon}
            interactive={false}
          />
        )
      })}

      {/* ── AIRPORT NODES ─────────────────────────────────────────────────── */}
      {airportList.map((ap) => {
        const status = warehouseStatus(ap, threshold)
        const color  = STATUS_COLOR[status]
        const pct    = occupancyPct(ap)
        return (
          <CircleMarker
            key={ap.id}
            center={[ap.lat, ap.lng]}
            radius={6}
            pathOptions={{
              color: theme === 'light' ? 'rgba(0,0,0,0.2)' : 'rgba(255,255,255,0.15)', weight: 1.5,
              fillColor: color, fillOpacity: 0.88,
            }}
            eventHandlers={{ click: () => onAirportClick && onAirportClick(ap) }}
          >
            <Tooltip className="tasf-tooltip" direction="top" offset={[0, -8]}>
              <strong>{ap.id}</strong> — {ap.name}<br />
              Warehouse: <span style={{ color }}><strong>{pct}%</strong></span> ({ap.currentOccupation} / {ap.warehouseCapacity})<br />
              Estado: <span style={{ color }}>
                {status === 'green' ? '● Normal' : status === 'amber' ? '● Alto' : '● Crítico'}
              </span>
            </Tooltip>
          </CircleMarker>
        )
      })}
    </MapContainer>
  )
}
