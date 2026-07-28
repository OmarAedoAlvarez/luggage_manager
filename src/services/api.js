const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'
const IS_DEV = import.meta.env.DEV

function debugLog(message, details) {
  if (!IS_DEV) return
  details !== undefined
    ? console.info(`[api] ${message}`, details)
    : console.info(`[api] ${message}`)
}

function toErrorMessage(error) {
  if (error instanceof Error) return error.message
  return String(error)
}

async function withHandling(action, fn) {
  try {
    return await fn()
  } catch (error) {
    throw new Error(`${action} failed: ${toErrorMessage(error)}`)
  }
}

async function toApiError(response) {
  const textBody = await response.text()
  if (!textBody) {
    return `HTTP ${response.status} ${response.statusText}`
  }

  try {
    const parsed = JSON.parse(textBody)
    if (parsed?.message) {
      return `HTTP ${response.status} ${parsed.message}`
    }
  } catch {
    // Fall through and return raw body.
  }

  return `HTTP ${response.status} ${response.statusText} - ${textBody}`
}

async function request(path, options = {}, timeoutMs = 10000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const method = options.method || 'GET'
  debugLog(`request ${method} ${path} -> ${BASE_URL}${path}`)
  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      mode: 'cors',
      credentials: 'omit',
      signal: controller.signal,
      ...options,
    })
    clearTimeout(timer)
    debugLog(`response ${method} ${path}`, { status: response.status, ok: response.ok })
    if (response.status === 204) {
      return null
    }
    if (!response.ok) {
      throw new Error(await toApiError(response))
    }
    // Some endpoints (e.g. LiveController.cancelFlight) return 200 with an
    // empty body (ResponseEntity<Void>). Attempting response.json() on an
    // empty body throws "Unexpected end of JSON input". Guard against that.
    const contentLength = response.headers.get('content-length')
    if (contentLength === '0') {
      return null
    }
    const contentType = response.headers.get('content-type') || ''
    if (!contentType.includes('application/json')) {
      return null
    }
    return response.json()
  } catch (error) {
    clearTimeout(timer)
    if (error.name === 'AbortError') {
      throw new Error(`${path} timed out after ${timeoutMs}ms`)
    }
    throw error
  }
}

export async function getLiveState(fromISO) {
  return withHandling('getLiveState', () =>
    request(`/live/state?from=${encodeURIComponent(fromISO)}`)
  )
}

export async function startSimulation(params) {
  return withHandling('startSimulation', () =>
    request('/simulation/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    }, 180000)  // 3 min — planning (SA/Tabu) can take 60–120s
  )
}

export async function registrarExperimento() {
  return withHandling('registrarExperimento', () =>
    request('/experimentos/registrar', { method: 'POST' })
  )
}

export async function exportarExperimentos() {
  return withHandling('exportarExperimentos', async () => {
    const res = await fetch(`${BASE_URL}/experimentos/export`, { mode: 'cors', credentials: 'omit' })
    if (res.status === 404) throw new Error('No hay experimentos registrados')
    if (!res.ok) throw new Error(await toApiError(res))
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'experimentos.csv'
    a.click()
    URL.revokeObjectURL(url)
  })
}

// ── Ops mode API ──────────────────────────────────────────────────

export async function getOpsState(fromISO) {
  return withHandling('getOpsState', () =>
    request(`/ops/state${fromISO ? `?from=${encodeURIComponent(fromISO)}` : ''}`)
  )
}

// Lightweight: warehouse occupancy only (no flights). Poll often for real-time.
export async function getOpsOccupancy(fromISO) {
  return withHandling('getOpsOccupancy', () =>
    request(`/ops/airports/occupancy${fromISO ? `?from=${encodeURIComponent(fromISO)}` : ''}`)
  )
}

export async function addOpsEnvio(dto) {
  return withHandling('addOpsEnvio', () =>
    request('/ops/envios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    })
  )
}

export async function planificarOps() {
  return withHandling('planificarOps', () =>
    request('/ops/planificar', { method: 'POST' })
  )
}

export async function getOpsEnvios() {
  return withHandling('getOpsEnvios', () => request('/ops/envios'))
}

export async function getOpsReporte() {
  return withHandling('getOpsReporte', () => request('/ops/reporte'))
}

export async function uploadOpsEnvios(file) {
  return withHandling('uploadOpsEnvios', async () => {
    const formData = new FormData()
    formData.append('file', file)
    const response = await fetch(`${BASE_URL}/upload/ops/envios`, {
      method: 'POST',
      body: formData,
      mode: 'cors',
      credentials: 'omit',
    })
    if (!response.ok) throw await toApiError(response)
    return response.json()
  })
}

export async function previewOpsEnvios(file) {
  return withHandling('previewOpsEnvios', async () => {
    const formData = new FormData()
    formData.append('file', file)
    const response = await fetch(`${BASE_URL}/upload/ops/envios/preview`, {
      method: 'POST',
      body: formData,
      mode: 'cors',
      credentials: 'omit',
    })
    if (!response.ok) throw new Error(await toApiError(response))
    return response.json()
  })
}

export async function batchSaveOpsEnvios(dtos) {
  return withHandling('batchSaveOpsEnvios', () =>
    request('/ops/envios/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dtos),
    })
  )
}

export const api = {
  startSimulation,

  getState: async (nowMin) => withHandling('getState', async () => {
    // nowMin = minuto simulado actual → KPIs de ocupación proyectados por ciclo de servicio.
    const qs = nowMin != null ? `?nowMin=${Math.floor(nowMin)}` : ''
    return request(`/simulation/state${qs}`, {}, 30000)  // VM uplink lenta: estado puede tardar >10s
  }),

  stepSimulation: async () => withHandling('stepSimulation', async () => {
    return request('/simulation/step', { method: 'POST' }, 180000)  // 3 min — día 3 (más maletas) planifica pesado
  }),

  stopSimulation: async () => withHandling('stopSimulation', async () => {
    return request('/simulation/stop', { method: 'POST' }, 60000)
  }),

  restartSimulation: async () => withHandling('restartSimulation', async () => {
    return request('/simulation/restart', { method: 'POST' }, 180000)  // 3 min — replanning
  }),

  resetSimulation: async () => withHandling('resetSimulation', async () => {
    await request('/simulation/reset', { method: 'POST' })
  }),

  getAirports: async () => withHandling('getAirports', async () => {
    return request('/airports')
  }),

  getAirportGraph: async () => withHandling('getAirportGraph', async () => {
    return request('/airports/graph')
  }),

  getFlights: async () => withHandling('getFlights', async () => {
    return request('/flights')
  }),

  getEnvios: async () => withHandling('getEnvios', async () => {
    return request('/envios')
  }),

  getEnvioById: async (id) => withHandling('getEnvioById', async () => {
    return request(`/envios/${id}`)
  }),

  getOpsEnvioById: async (id) => withHandling('getOpsEnvioById', async () => {
    return request(`/ops/envios/${id}`)
  }),

  cancelFlight: async (codigoVuelo, aplicaDesde = 'HOY') => withHandling('cancelFlight', async () => {
    return request(`/simulation/cancel-flight/${codigoVuelo}?aplicaDesde=${aplicaDesde}`, { method: 'POST' }, 180000)
  }),

  cancelLiveFlight: async (codigoVuelo, aplicaDesde = 'HOY') => withHandling('cancelLiveFlight', async () => {
    return request(`/live/cancel-flight/${codigoVuelo}?aplicaDesde=${aplicaDesde}`, { method: 'POST' }, 180000)
  }),

  cancelOpsFlight: async (codigoVuelo, aplicaDesde = 'HOY') => withHandling('cancelOpsFlight', async () => {
    return request(`/ops/cancel-flight/${codigoVuelo}?aplicaDesde=${aplicaDesde}`, { method: 'POST' }, 180000)
  }),

  clearOpsCancellations: async () => withHandling('clearOpsCancellations', async () => {
    return request(`/ops/cancellations`, { method: 'DELETE' })
  }),

  cancelEnvio: async (idEnvio) => withHandling('cancelEnvio', async () => {
    return request(`/simulation/cancel-envio/${idEnvio}`, { method: 'POST' })
  }),

  getEnviosByFlight: async (code) => withHandling('getEnviosByFlight', async () => {
    return request(`/flights/${code}/envios`)
  }),

  // All routes an envío's bags take (a split envío returns several). Each route carries its
  // escalas with flight code, airports and estimated times — for drawing on the map on demand.
  getEnvioRutas: async (id) => withHandling('getEnvioRutas', async () => {
    return request(`/envios/${encodeURIComponent(id)}/rutas`)
  }),

  // The single route a specific maleta follows (its plan version).
  getMaletaRuta: async (id) => withHandling('getMaletaRuta', async () => {
    return request(`/maletas/${encodeURIComponent(id)}/ruta`)
  }),

  getOpsEnviosByFlight: async (code, esDiaSiguiente) => withHandling('getOpsEnviosByFlight', async () => {
    const qs = esDiaSiguiente != null ? `?esDiaSiguiente=${esDiaSiguiente}` : ''
    return request(`/ops/flights/${code}/envios${qs}`)
  }),

  getAirportInventory: async (iata, nowMin) => withHandling('getAirportInventory', async () => {
    const qs = nowMin != null ? `?nowMin=${Math.floor(nowMin)}` : ''
    return request(`/airports/${iata}/inventory${qs}`)
  }),

  getOpsAirportInventory: async (iata) => withHandling('getOpsAirportInventory', async () => {
    return request(`/ops/airports/${iata}/inventory`)
  }),

  getLiveState: async (fromISO) => withHandling('getLiveState', () =>
    request(`/live/state?from=${encodeURIComponent(fromISO)}`)
  ),

  uploadEnvios: async (file) => withHandling('uploadEnvios', async () => {
    const formData = new FormData()
    formData.append('file', file)
    const response = await fetch(`${BASE_URL}/upload/envios`, {
      method: 'POST',
      mode: 'cors',
      credentials: 'omit',
      body: formData,
    })
    if (!response.ok) {
      throw new Error(await toApiError(response))
    }
    return response.json()
  }),

  uploadOpsEnvios: async (file) => uploadOpsEnvios(file),
  previewOpsEnvios: async (file) => previewOpsEnvios(file),
  batchSaveOpsEnvios: async (dtos) => batchSaveOpsEnvios(dtos),
  addOpsEnvio: async (dto) => addOpsEnvio(dto),
  planificarOps: async () => planificarOps(),
  getOpsEnvios: async () => getOpsEnvios(),
  getOpsReporte: async () => getOpsReporte(),
  getOpsEnviosEntregados: async (horas = 4) => withHandling('getOpsEnviosEntregados', () =>
    request(`/ops/envios/entregados?horas=${horas}`)
  ),
}
