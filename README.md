# TASF.B2B Dashboard

Dashboard frontend para simulacion de operaciones de equipaje en rutas aereas.

## 1. Objetivo del proyecto

Este repositorio implementa un prototipo visual de centro de operaciones con:

- mapa global de rutas y aeropuertos,
- feed de vuelos y estado SLA,
- KPIs de simulacion en tiempo real,
- paneles de filtros y umbrales,
- grafico historico de throughput/SLA.

No tiene backend ni persistencia. Toda la logica corre en frontend.

## 2. Stack tecnico

- React 18
- Vite 5
- Leaflet + react-leaflet
- Chart.js + react-chartjs-2

## 3. Ejecutar en local

Requisitos:

- Node.js 18+
- npm

Comandos:

```bash
npm install
npm run dev
```

Build de produccion:

```bash
npm run build
npm run preview
```

## 4. Estructura principal

- `src/App.jsx`: orquestacion de estado global y reloj de simulacion.
- `src/data.js`: datos base + funciones de simulacion + reglas de estado.
- `src/components/TopBar.jsx`: KPIs y controles de simulacion.
- `src/components/LeftPanel.jsx`: filtros/umbrales/capas.
- `src/components/MapView.jsx`: mapa y render de rutas/aeropuertos.
- `src/components/RightPanel.jsx`: feed de vuelos y resumen operacional.
- `src/components/PerfChart.jsx`: grafico de throughput semanal.
- `src/index.css`: tema visual global y tokens.

## 5. Documentacion para handoff a otra IA

Documento tecnico principal:

- `docs/AI_HANDOFF.md`

Incluye arquitectura, contratos de datos, flujo de simulacion, limites actuales y plan sugerido para evolucionar a sistema real.

## 6. Nota importante

Existe un archivo `tasf_dashboard.html` en raiz con bundle minificado historico. El codigo fuente vigente para evolucionar el proyecto esta en `src/`.
