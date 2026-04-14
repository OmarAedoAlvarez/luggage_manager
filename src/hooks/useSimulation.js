import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../services/api.js'
import { usePolling } from './usePolling.js'

export function useSimulation() {
  const [state, setState] = useState(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState(null)
  const [autoSync, setAutoSync] = useState(true)

  const isRunning = useMemo(() => Boolean(state?.enEjecucion), [state])

  const fetchState = useCallback(async () => {
    const nextState = await api.getState()
    setState(nextState)
    return nextState
  }, [])

  const { data: polledData, error: pollingError } = usePolling(fetchState, 2000, autoSync)

  useEffect(() => {
    if (polledData !== null) {
      setState(polledData)
    }
  }, [polledData])

  useEffect(() => {
    if (!pollingError) return
    setError(pollingError)
  }, [pollingError])

  const start = useCallback(async (params, files) => {
    setIsLoading(true)
    setError(null)
    try {
      const nextState = await api.startSimulation(params, files)
      setState(nextState)
      return nextState
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      throw err
    } finally {
      setIsLoading(false)
    }
  }, [])

  const step = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const nextState = await api.stepSimulation()
      setState(nextState)
      return nextState
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      throw err
    } finally {
      setIsLoading(false)
    }
  }, [])

  const reset = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      await api.resetSimulation()
      setState(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      throw err
    } finally {
      setIsLoading(false)
    }
  }, [])

  const refresh = useCallback(async () => {
    setError(null)
    try {
      return await fetchState()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      throw err
    }
  }, [fetchState])

  return {
    state,
    isRunning,
    isLoading,
    error,
    autoSync,
    setAutoSync,
    start,
    step,
    reset,
    refresh,
  }
}
