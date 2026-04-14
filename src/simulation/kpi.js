export function getSimulatedKPIs(routes, airports) {
  const bagsInTransit = routes
    .filter((r) => r.status !== 'red')
    .reduce((sum, route) => sum + route.bags, 0)

  const bagsDelivered = routes
    .filter((r) => r.status === 'green')
    .reduce((sum, route) => sum + Math.round(route.bags * 0.6), 0)

  const slaViolated = routes.filter((r) => r.status === 'red').length
  const totalRoutes = routes.length || 1
  const slaCompliance = Math.round((routes.filter((r) => r.status === 'green').length / totalRoutes) * 1000) / 10

  const activeFlights = routes.filter((r) => r.status !== 'red').length
  const avgWarehouse = airports.length
    ? Math.round(
      (airports.reduce((sum, airport) => sum + ((airport.currentOccupation / airport.warehouseCapacity) * 100), 0) / airports.length) * 10,
    ) / 10
    : 0

  return {
    bagsInTransit,
    bagsDelivered,
    slaCompliance,
    activeFlights,
    slaViolated,
    avgWarehouse,
  }
}
