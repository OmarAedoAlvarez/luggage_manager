import { useEffect, useRef, useState } from 'react'

export function usePolling(fetchFn, intervalMs, enabled) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const timerRef = useRef(null)

  useEffect(() => {
    let active = true

    async function run() {
      setLoading(true)
      setError(null)
      try {
        const value = await fetchFn()
        if (active) setData(value)
      } catch (err) {
        if (active) {
          setError(err instanceof Error ? err.message : String(err))
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    if (enabled) {
      run()
      timerRef.current = setInterval(run, intervalMs)
    }

    return () => {
      active = false
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }, [fetchFn, intervalMs, enabled])

  return { data, loading, error }
}
