import React, { useMemo } from 'react'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  LineElement,
  PointElement,
  Filler,
  Tooltip,
} from 'chart.js'
import { Line } from 'react-chartjs-2'

ChartJS.register(CategoryScale, LinearScale, LineElement, PointElement, Filler, Tooltip)

export default function ColapsoScreen({ simState, theme, onBack }) {
  const cp = simState?.colapsoPunto
  const historial = simState?.throughputHistorial || simState?.throughputHistory || []
  const aeropuertos = simState?.aeropuertos || []
  const envios = simState?.envios || []

  const slaData = useMemo(() =>
    historial.map((h) => {
      const total = (h.slaOk || 0) + (h.slaBreach || 0)
      return { dia: h.dia, pct: total > 0 ? Math.round((h.slaBreach * 100 / total) * 10) / 10 : 0 }
    }),
  [historial])

  const topAps = useMemo(() => {
    if (cp?.topAeropuertos?.length) {
      return cp.topAeropuertos.map((iata) => {
        const ap = aeropuertos.find((a) => (a.codigoIATA || a.id) === iata)
        const cap = ap?.capacidadAlmacen ?? ap?.warehouseCapacity ?? 0
        const ocu = ap?.ocupacionActual ?? ap?.currentOccupation ?? 0
        return { iata, ciudad: ap?.ciudad ?? ap?.name ?? '', pct: cap > 0 ? Math.round((ocu / cap) * 100) : 0 }
      })
    }
    return aeropuertos
      .filter((a) => (a.capacidadAlmacen ?? a.warehouseCapacity ?? 0) > 0)
      .map((a) => {
        const cap = a.capacidadAlmacen ?? a.warehouseCapacity ?? 0
        const ocu = a.ocupacionActual ?? a.currentOccupation ?? 0
        return { iata: a.codigoIATA || a.id, ciudad: a.ciudad ?? a.name ?? '', pct: Math.round((ocu / cap) * 100) }
      })
      .sort((a, b) => b.pct - a.pct)
      .slice(0, 5)
  }, [cp, aeropuertos])

  const topRetrasados = useMemo(() =>
    envios.filter((e) => e.estado === 'RETRASADO').slice(0, 5),
  [envios])

  const totalRetrasados = useMemo(() =>
    envios.filter((e) => e.estado === 'RETRASADO').length,
  [envios])

  const isDark = theme === 'dark'
  const gridColor = isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
  const mutedColor = isDark ? '#768390' : '#888'
  const textColor = isDark ? '#e6edf3' : '#1c2128'

  const chartData = {
    labels: slaData.map((d) => `D${d.dia}`),
    datasets: [{
      label: '% SLA vencido',
      data: slaData.map((d) => d.pct),
      borderColor: '#f04b4b',
      backgroundColor: 'rgba(240,75,75,0.08)',
      borderWidth: 2,
      pointRadius: slaData.map((d) => (d.dia === cp?.dia ? 6 : 2)),
      pointBackgroundColor: '#f04b4b',
      fill: true,
      tension: 0.3,
    }],
  }

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: isDark ? 'rgba(22,27,34,0.95)' : '#fff',
        titleColor: textColor,
        bodyColor: mutedColor,
        borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)',
        borderWidth: 1,
        callbacks: { label: (ctx) => ` ${ctx.parsed.y}% SLA vencido` },
      },
    },
    scales: {
      x: {
        grid: { color: gridColor },
        ticks: { color: mutedColor, font: { family: 'Space Mono', size: 10 } },
      },
      y: {
        grid: { color: gridColor },
        ticks: { color: mutedColor, font: { family: 'Space Mono', size: 10 }, callback: (v) => `${v}%` },
        min: 0, max: 100,
      },
    },
  }

  const panelStyle = {
    background: 'var(--panel)',
    border: '1px solid var(--border)',
    borderRadius: 8,
    padding: '16px 20px',
  }

  const monoSm = { fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', letterSpacing: 1.5, textTransform: 'uppercase' }

  const thStyle = { textAlign: 'left', padding: '4px 0', color: 'var(--muted)', fontWeight: 400, fontSize: 10, fontFamily: 'var(--mono)' }
  const tdStyle = { padding: '6px 0', fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--text)', borderBottom: '1px solid rgba(128,128,128,0.1)' }

  if (!cp) {
    return (
      <div style={{ padding: 40, color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 13 }}>
        No se detectó colapso en esta simulación.
      </div>
    )
  }

  return (
    <div style={{ padding: '32px 40px', maxWidth: 960, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 32 }}>
        <button
          onClick={onBack}
          style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 11, padding: '4px 10px', borderRadius: 4, cursor: 'pointer' }}
        >
          ← VOLVER
        </button>
        <h1 style={{ fontFamily: 'var(--mono)', fontSize: 16, fontWeight: 700, color: 'var(--red)', letterSpacing: 2, margin: 0 }}>
          REPORTE DE COLAPSO
        </h1>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 24 }}>
        {[
          { label: 'Día del colapso', value: `Día ${cp.dia}`, color: 'var(--red)' },
          { 
            label: cp.tipo === 'ALMACEN' ? 'Ocupación almacén' : 'SLA vencido',
            value: cp.tipo === 'ALMACEN' ? `${cp.porcentajeOcupacion || cp.pctSlaVencido}%` : `${cp.pctSlaVencido}%`,
            color: 'var(--red)'
          },
          { 
            label: cp.tipo === 'ALMACEN' ? 'Almacén colapsado' : 'Aeropuerto crítico',
            value: cp.aeropuertoMasCritico,
            color: 'var(--amber)'
          },
        ].map((kpi) => (
          <div key={kpi.label} style={panelStyle}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 24, fontWeight: 700, color: kpi.color, lineHeight: 1 }}>{kpi.value}</div>
            <div style={{ fontFamily: 'var(--sans)', fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1.4, marginTop: 6 }}>{kpi.label}</div>
          </div>
        ))}
      </div>

      {slaData.length > 0 ? (
        <div style={{ ...panelStyle, marginBottom: 24 }}>
          <div style={{ ...monoSm, marginBottom: 16 }}>% SLA vencido por día</div>
          <div style={{ height: 200 }}>
            <Line data={chartData} options={chartOptions} />
          </div>
        </div>
      ) : (
        <div style={{ ...panelStyle, marginBottom: 24, color: 'var(--muted)', fontFamily: 'var(--mono)', fontSize: 12 }}>
          Sin historial de throughput disponible para este período.
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div style={panelStyle}>
          <div style={{ ...monoSm, marginBottom: 12 }}>{cp.tipo === 'ALMACEN' ? 'Top aeropuertos con mayor ocupación' : 'Top aeropuertos críticos'}</div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={thStyle}>Aeropuerto</th>
                <th style={{ ...thStyle, textAlign: 'right' }}>Ocupación</th>
              </tr>
            </thead>
            <tbody>
              {topAps.map((ap, i) => (
                <tr key={ap.iata}>
                  <td style={tdStyle}>
                    {i + 1}. {ap.iata}
                    {ap.ciudad && (
                      <span style={{ color: 'var(--muted)', fontSize: 10, marginLeft: 6 }}>{ap.ciudad}</span>
                    )}
                  </td>
                  <td style={{ ...tdStyle, textAlign: 'right', color: ap.pct >= 85 ? 'var(--red)' : ap.pct >= 60 ? 'var(--amber)' : 'var(--green)' }}>{ap.pct}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div style={panelStyle}>
          <div style={{ ...monoSm, marginBottom: 12 }}>
            Envíos retrasados ({topRetrasados.length} de {totalRetrasados})
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={thStyle}>ID envío</th>
                <th style={{ ...thStyle, textAlign: 'center' }}>Ruta</th>
                <th style={{ ...thStyle, textAlign: 'right' }}>Maletas</th>
              </tr>
            </thead>
            <tbody>
              {topRetrasados.length === 0 ? (
                <tr><td colSpan={3} style={{ ...tdStyle, color: 'var(--muted)' }}>Sin envíos retrasados</td></tr>
              ) : topRetrasados.map((e) => (
                <tr key={e.idEnvio}>
                  <td style={tdStyle}>{e.idEnvio}</td>
                  <td style={{ ...tdStyle, textAlign: 'center', fontSize: 10, color: 'var(--muted)' }}>{e.aeropuertoOrigen}→{e.aeropuertoDestino}</td>
                  <td style={{ ...tdStyle, textAlign: 'right', color: 'var(--red)' }}>{e.cantidadMaletas}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
