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

export const api = {
  startSimulation: async (params, files) => withHandling('startSimulation', async () => {
    const formData = new FormData()
    formData.append('params', JSON.stringify(params))
    ;(files || []).forEach((file) => formData.append('files', file))

    debugLog('startSimulation payload prepared', {
      endpoint: `${BASE_URL}/simulation/start`,
      params,
      fileCount: files?.length || 0,
      fileNames: (files || []).map((file) => file.name),
      fileSizes: (files || []).map((file) => file.size),
    })

    try {
      const airportsProbe = await fetch(`${BASE_URL}/airports`, {
        method: 'GET',
        mode: 'cors',
        credentials: 'omit',
      })
      debugLog('startSimulation preflight probe GET /airports', {
        status: airportsProbe.status,
        ok: airportsProbe.ok,
      })
    } catch (probeErr) {
      debugLog('startSimulation preflight probe GET /airports failed', {
        error: probeErr instanceof Error ? probeErr.message : String(probeErr),
      })
    }

    try {
      const optionsProbe = await fetch(`${BASE_URL}/simulation/start`, {
        method: 'OPTIONS',
        mode: 'cors',
        credentials: 'omit',
      })
      debugLog('startSimulation preflight probe OPTIONS /simulation/start', {
        status: optionsProbe.status,
        ok: optionsProbe.ok,
        allowOrigin: optionsProbe.headers.get('access-control-allow-origin'),
        allowMethods: optionsProbe.headers.get('access-control-allow-methods'),
      })
    } catch (probeErr) {
      debugLog('startSimulation preflight probe OPTIONS failed', {
        error: probeErr instanceof Error ? probeErr.message : String(probeErr),
      })
    }

    try {
      return await request('/simulation/start', {
        method: 'POST',
        body: formData,
      })
    } catch (error) {
      debugLog('startSimulation request failed', {
        endpoint: `${BASE_URL}/simulation/start`,
        error: error instanceof Error ? error.message : String(error),
      })
      throw error
    }
  }),

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
