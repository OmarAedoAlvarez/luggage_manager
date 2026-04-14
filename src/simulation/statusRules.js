export const STATUS_COLOR = {
  green: '#22d07a',
  amber: '#f5a623',
  red: '#f04b4b',
}

export function getRouteStatus(route) {
  if (route.slaViolated) return 'red'
  if (route.cancelled || route.replanified) return 'amber'
  return 'green'
}

export function getWarehouseStatus(load, threshold) {
  if (load >= threshold) return 'red'
  if (load >= threshold - 20) return 'amber'
  return 'green'
}
