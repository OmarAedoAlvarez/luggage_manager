import React from 'react'
import { Bar } from 'react-chartjs-2'
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip)

function slaColor(slaOk, total) {
  const pct = total > 0 ? slaOk / total : 0
  if (pct >= 0.92) return 'rgba(34,208,122,0.80)'
  if (pct >= 0.78) return 'rgba(245,166,35,0.80)'
  return 'rgba(240,75,75,0.80)'
}

export default function PerfChart({ throughputHistory }) {
  if (!throughputHistory || throughputHistory.length === 0) {
    return (
      <div style={{
        height: 72, background: 'var(--panel)', borderTop: '1px solid var(--border)',
        display: 'flex', alignItems: 'center', padding: '0 16px', flexShrink: 0,
      }}>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--muted)' }}>
          Sin datos de throughput aún
        </span>
      </div>
    )
  }

  const data = {
    labels: throughputHistory.map((t) => t.day),
    datasets: [
      {
        label: 'SLA Cumplido',
        data: throughputHistory.map((t) => t.slaOk),
        backgroundColor: throughputHistory.map((t) => slaColor(t.slaOk, t.bagsProcessed)),
        borderRadius: 3, borderWidth: 0,
      },
      {
        label: 'SLA Vencido',
        data: throughputHistory.map((t) => Math.max(0, t.bagsProcessed - t.slaOk)),
        backgroundColor: 'rgba(240,75,75,0.25)',
        borderRadius: 3, borderWidth: 0,
      },
    ],
  }

  const options = {
    responsive: true, maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: 'rgba(13,17,23,0.97)',
        borderColor: 'rgba(99,152,255,0.22)', borderWidth: 1,
        titleColor: '#e6edf3', bodyColor: '#6e7f9a',
        titleFont: { family: "'DM Mono',monospace", size: 10 },
        bodyFont:  { family: "'DM Mono',monospace", size: 10 },
        padding: 8,
        callbacks: {
          title: (items) => items[0]?.label,
          label: (ctx) => {
            const t = throughputHistory[ctx.dataIndex]
            if (ctx.datasetIndex === 0) return `SLA ok: ${ctx.parsed.y}`
            return `SLA venc: ${ctx.parsed.y} (${t.bagsProcessed} total)`
          },
        },
      },
    },
    scales: {
      x: {
        stacked: true,
        ticks: { color: '#6e7f9a', font: { size: 9, family: "'DM Mono',monospace" } },
        grid: { display: false }, border: { display: false },
      },
      y: {
        stacked: true,
        ticks: { color: '#6e7f9a', font: { size: 9, family: "'DM Mono',monospace" }, maxTicksLimit: 3 },
        grid: { color: 'rgba(255,255,255,0.04)' }, border: { display: false },
      },
    },
  }

  return (
    <div style={{
      height: 80, background: 'var(--panel)', borderTop: '1px solid var(--border)',
      padding: '8px 16px', display: 'flex', alignItems: 'center', gap: 16, flexShrink: 0,
    }}>
      {/* legend */}
      <div style={{ flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 5 }}>
        <div style={{ fontFamily: 'var(--sans)', fontSize: 7, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 1.6, marginBottom: 2, fontWeight: 700 }}>
          Throughput / Día
        </div>
        {[['#22d07a', 'SLA cumplido'], ['#f04b4b', 'SLA vencido']].map(([c, l]) => (
          <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <div style={{ width: 7, height: 7, borderRadius: 1, background: c, flexShrink: 0 }} />
            <span style={{ fontFamily: 'var(--mono)', fontSize: 8, color: 'var(--muted)' }}>{l}</span>
          </div>
        ))}
      </div>
      {/* chart */}
      <div style={{ flex: 1, height: 62 }}>
        <Bar data={data} options={options} />
      </div>
    </div>
  )
}
