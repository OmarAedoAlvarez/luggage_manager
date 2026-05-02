const BASE_URL = 'http://localhost:8080/api'

function debugLog(message, details) {
  if (details !== undefined) {
    console.info(`[api] ${message}`, details)
    return
  }
  console.info(`[api] ${message}`)
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

async function request(path, options = {}) {
  const method = options.method || 'GET'
  debugLog(`request ${method} ${path} -> ${BASE_URL}${path}`)
  const response = await fetch(`${BASE_URL}${path}`, {
    mode: 'cors',
    credentials: 'omit',
    ...options,
  })
  debugLog(`response ${method} ${path}`, { status: response.status, ok: response.ok })
  if (response.status === 204) {
    return null
  }
  if (!response.ok) {
    throw new Error(await toApiError(response))
  }
  return response.json()
}

export async function startSimulation(params) {
  return withHandling('startSimulation', () =>
    request('/simulation/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    })
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

export const api = {
  startSimulation,

  getState: async () => withHandling('getState', async () => {
    return request('/simulation/state')
  }),

  stepSimulation: async () => withHandling('stepSimulation', async () => {
    return request('/simulation/step', { method: 'POST' })
  }),

  resetSimulation: async () => withHandling('resetSimulation', async () => {
    await request('/simulation/reset', { method: 'POST' })
  }),

  getAirports: async () => withHandling('getAirports', async () => {
    return request('/airports')
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

  cancelFlight: async (codigoVuelo) => withHandling('cancelFlight', async () => {
    return request(`/simulation/cancel-flight/${codigoVuelo}`, { method: 'POST' })
  }),

  cancelEnvio: async (idEnvio) => withHandling('cancelEnvio', async () => {
    return request(`/simulation/cancel-envio/${idEnvio}`, { method: 'POST' })
  }),
}
