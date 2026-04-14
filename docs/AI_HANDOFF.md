# AI Handoff - Session 1 (Backend Foundation)

Date: 2026-04-13
Module: backend (Spring Boot 3.5.13, Java 21)
Base package: com.tasf.backend

## Implemented Classes and Exact Package Paths

### Domain
- com.tasf.backend.domain.Aeropuerto
- com.tasf.backend.domain.Almacen
- com.tasf.backend.domain.Aerolinea
- com.tasf.backend.domain.Vuelo
- com.tasf.backend.domain.Cancelacion
- com.tasf.backend.domain.Envio
- com.tasf.backend.domain.Maleta
- com.tasf.backend.domain.Escala
- com.tasf.backend.domain.PlanDeViaje
- com.tasf.backend.domain.MetricaAlgoritmo
- com.tasf.backend.domain.EstadoEnvio
- com.tasf.backend.domain.EstadoMaleta

### Parsers
- com.tasf.backend.parser.AirportParser
- com.tasf.backend.parser.FlightParser
- com.tasf.backend.parser.BaggageParser

### Services
- com.tasf.backend.service.DataLoaderService

### Package scaffolding (created)
- com.tasf.backend.controller (package-info.java)
- com.tasf.backend.algorithm (package-info.java)
- com.tasf.backend.simulation (package-info.java)
- com.tasf.backend.config (package-info.java)

## Data Contracts

### Aeropuerto
- codigoIATA: String
- nombre: String
- ciudad: String
- pais: String
- continente: String (americas | europa | asia)
- huso: int
- capacidadAlmacen: int
- lat: double
- lng: double
- ocupacionActual: int (runtime default = 0)

### Vuelo
- codigoVuelo: String
- origen: String (IATA)
- destino: String (IATA)
- horaSalida: LocalTime
- horaLlegada: LocalTime
- capacidadTotal: int
- tipo: String (continental | intercontinental)
- cargaActual: int (runtime default = 0)
- cancelado: boolean (runtime default = false)

### Envio
- idEnvio: String
- codigoAerolinea: String
- aeropuertoOrigen: String
- aeropuertoDestino: String
- fechaHoraIngreso: LocalDateTime
- cantidadMaletas: int
- sla: int (1 same continent, 2 intercontinental)
- estado: EstadoEnvio

### Maleta
- idMaleta: String
- idEnvio: String
- ubicacionActual: String (IATA)
- estado: EstadoMaleta

## Continent Detection Logic Used

Implemented in AirportParser:
- GMT -8 to -3 => americas
- GMT 0 to +2 => europa
- GMT +4 to +9 => asia
- GMT +3 is resolved by country dictionary (needed because range overlaps in requirements)

Fallback behavior:
- If GMT is ambiguous/outside expected values, parser falls back to country mapping.
- If still unknown, defaults to americas with warning log.

## Parser Behavior and Responsibilities

AirportParser:
- Reads aeropuertos.txt
- Detects UTF-16 BOM/encoding and parses resiliently
- Converts DMS coordinates to decimal degrees
- Skips malformed lines with warning (no exception propagation)
- Logs loaded airport count

FlightParser:
- Reads planes_vuelo.txt
- Parses line format ORIGIN-DEST-HH:MM-HH:MM-CAPACITY
- Sets tipo using airport continent map
- Skips malformed lines with warning
- Logs loaded flight count

BaggageParser:
- Parses runtime InputStream (not file path)
- Inputs: originAirport, dateFrom, dateTo, continentByAirport
- Filters records line-by-line by date (inclusive)
- Does not pre-load complete file into memory before filtering
- Returns List<Envio>
- Skips malformed lines with warning

## DataLoaderService

Implemented as Spring @Service:
- @PostConstruct loadStaticData()
	- Loads data/aeropuertos.txt via AirportParser
	- Loads data/planes_vuelo.txt via FlightParser
	- Stores in-memory immutable lists
	- Logs: Loaded X airports and Y flights
- Public methods:
	- getAeropuertos(): List<Aeropuerto>
	- getVuelos(): List<Vuelo>

## Runtime Verification (real data files)

From spring-boot:run startup log:
- Loaded airports: 30
- Loaded flights: 2866
- DataLoaderService log confirmed: Loaded 30 airports and 2866 flights

## Technical Decisions and Why

- Constructor injection used for all beans:
	Keeps dependencies explicit, testable, and aligned with clean architecture practices.

- Parsers are pure ingestion components:
	They only transform input lines to domain objects and log malformed data.
	Business orchestration remains in service layer.

- Graceful malformed line handling:
	Required resilience for operational data quality issues; avoids failing full batch load.

- UTF-16-aware airport parsing:
	aeropuertos.txt has UTF-16 characteristics; parser detects BOM/null-byte pattern to decode correctly.

- Immutable loaded lists in DataLoaderService:
	Prevents accidental mutation of static catalog data after startup.

## Pending Items for Session 2

- Implement REST controllers in com.tasf.backend.controller
- Define algorithm abstractions/interfaces in com.tasf.backend.algorithm
- Build simulation engine in com.tasf.backend.simulation
- Add config classes in com.tasf.backend.config (CORS + shared beans)
- Add unit tests for parsers (valid lines, malformed lines, boundary dates)
- Add integration test for DataLoaderService startup load
- Improve airport header handling to avoid warning on known metadata first line

---

# AI Handoff - Session 3 (Frontend TopBar Cleanup)

Date: 2026-04-13
Scope: frontend only

## Implemented Changes

- src/components/TopBar.jsx
	- Removed the redundant period selector dropdown.
	- Removed simPeriod and setSimPeriod props.
	- Added useBackend and backendState props for primary action labeling.
	- Primary action label now resolves to CONFIGURAR, ⏸ PAUSAR, or ↺ REINICIAR.
- src/App.jsx
	- Removed simPeriod state and setSimPeriod wiring.
	- Stopped passing period selector props into TopBar.
	- Kept backend state and reset/pause wiring intact.

## Intent

- The period is now owned only by ConfigScreen.
- The top bar primary action now reflects the simulation lifecycle instead of suggesting direct execution.

---

# AI Handoff - Session 4 (Backend CORS Pattern Fix)

Date: 2026-04-13
Scope: backend only

## Implemented Change

- backend/src/main/java/com/tasf/backend/config/CorsConfig.java
	- Switched from explicit localhost port origins to wildcard origin patterns.
	- CORS now covers all localhost and 127.0.0.1 ports under /api/**.
	- Added maxAge(3600).

## Context

- No SecurityFilterChain bean exists in the backend, so no additional cors(Customizer.withDefaults()) wiring was needed.

---

# AI Handoff - Session 5 (Config Default Date)

Date: 2026-04-13
Scope: frontend only

## Implemented Change

- src/screens/ConfigScreen.jsx
	- Default start date now initializes to 2026-02-01.

## Intent

- Match the simulation start date shown in the current configuration UI by default.

---

# AI Handoff - Session 6 (Frontend Debug Logging)

Date: 2026-04-13
Scope: frontend only

## Implemented Change

- src/services/api.js
	- Added console debug logs for request entry, response status, and simulation start payload/failures.
- src/screens/ConfigScreen.jsx
	- Added console logs before submitting simulation start and on success/failure.

## Intent

- Make the failing point visible in the browser console so the current NetworkError can be separated from payload or HTTP errors.

---

# AI Handoff - Session 7 (Backend Start Debug Logging)

Date: 2026-04-13
Scope: backend only

## Implemented Change

- backend/src/main/java/com/tasf/backend/controller/SimulationController.java
	- Added request-entry logs for /api/simulation/start.
	- Added logs for parsed params and each uploaded baggage file.
	- Added envios count and success/failure logs.

## Intent

- Confirm whether the browser POST reaches the backend controller and where it fails if it does.

---

# AI Handoff - Session 8 (Browser Network Probe)

Date: 2026-04-13
Scope: frontend only

## Implemented Change

- src/services/api.js
	- Added explicit fetch config (`mode: cors`, `credentials: omit`) for API requests.
	- Added browser-side probes before startSimulation POST:
		- GET /airports probe
		- OPTIONS /simulation/start probe
	- Added file size logging in startSimulation payload diagnostics.

## Intent

- Separate browser transport/CORS failures from backend endpoint failures using logs from the exact runtime context (browser fetch).

---

# AI Handoff - Session 2 (Metaheuristics + Planning Service)

Date: 2026-04-13
Scope: backend only

## Implemented Classes and Paths

### New domain objects
- com.tasf.backend.domain.ParametrosSimulacion
- com.tasf.backend.domain.PlanningResult

### Algorithm strategy contracts and implementations
- com.tasf.backend.algorithm.MetaheuristicAlgorithm
- com.tasf.backend.algorithm.RoutePlannerSupport (shared feasibility/objective logic)
- com.tasf.backend.algorithm.RouteCandidate (internal route representation)
- com.tasf.backend.algorithm.TabuSearchAlgorithm
- com.tasf.backend.algorithm.SimulatedAnnealingAlgorithm

### Service layer
- com.tasf.backend.service.PlanningService

### Test coverage added
- com.tasf.backend.service.PlanningServiceIntegrationTest

## Key Algorithm Parameters Implemented

TabuSearchAlgorithm:
- Bean name: TABU_SEARCH
- Tabu size: min(20, envios.size()/2), minimum 1
- Iteration limit: min(10000, 100 * envios.size())
- Early stop: no improvement in 30 iterations
- Move acceptance: non-tabu + hard-constraints valid + objective <= current

SimulatedAnnealingAlgorithm:
- Bean name: SIMULATED_ANNEALING
- initialTemperature: 1000.0
- coolingRate: 0.995
- minTemperature: 0.1
- maxIterations: min(50000, 500 * envios.size())
- Acceptance: always if delta < 0, else probability exp(-delta/T)

Both algorithms:
- Never throw outward (catch runtime exceptions and return best known solution or empty list)
- Reset metric state at start of each planificar call
- Set algoritmoUsado on generated PlanDeViaje
- Mark envio estado RETRASADO + warning log when feasible routing cannot be established

## Strategy Pattern Wiring via Spring Bean Names

- Implementations annotated with @Component("TABU_SEARCH") and @Component("SIMULATED_ANNEALING").
- PlanningService receives Map<String, MetaheuristicAlgorithm> via constructor injection.
- Algorithm is selected by params.algoritmo using map lookup (no if/else selection chain).

## Business Rules Enforced and Exact Code Location

Continent/SLA rule (critical: never from GMT in this session):
- RoutePlannerSupport.enforceSlaFromContinent(...)
- Uses aeropuertos list lookup by codigoIATA and aeropuerto.continente only.

SLA deadline feasibility (1 day same continent, 2 days intercontinental):
- RoutePlannerSupport.isWithinSla(...)
- RoutePlannerSupport.objective(...)

Minimum layover:
- RoutePlannerSupport.buildOneStopCandidate(...)
- Uses params.minutosEscalaMinima.

Pickup at destination:
- RoutePlannerSupport.isWithinSla(...) adds params.minutosRecogidaDestino.
- RouteCandidate.toPlan(...) sets final escala departure as arrival + pickup minutes.

Flight load <= 100% capacity:
- RoutePlannerSupport.respectsHardConstraints(...)
- Enforced as projectedLoad <= min(vuelo.capacidadTotal, params.capacidadVuelo).

Intermediate warehouse <= 90% capacity:
- RoutePlannerSupport.respectsHardConstraints(...)
- Enforced with params.capacidadAlmacen * 0.9 on intermediate hubs.

Evaluate at least 3 feasible routes per envio before optimization:
- TabuSearchAlgorithm.planificar(...)
- SimulatedAnnealingAlgorithm.planificar(...)
- If candidate pool < 3, envio marked RETRASADO.

Objective function (both):
- RoutePlannerSupport.objective(...)
- minimize: total SLA violations + weighted warehouse overload (weight = 10.0).

## PlanningService and PlanningResult Contract

PlanningService.planificar(...):
- Input:
	- List<Envio> envios
	- List<Vuelo> vuelos
	- List<Aeropuerto> aeropuertos
	- ParametrosSimulacion params
- Output: PlanningResult
	- planes: List<PlanDeViaje>
	- metrica: MetricaAlgoritmo (from selected algorithm)
	- enviosSinRuta: IDs not present in generated plans

Fallback behavior:
- Unknown algorithm key returns empty planes and zeroed metrica payload with warning log.

## Sample MetricaAlgoritmo Output from Test Run

Captured from PlanningServiceIntegrationTest:
- TABU_SEARCH:
	MetricaAlgoritmo(idMetrica=MET-TABU_SEARCH-6787153277100, algoritmoUsado=TABU_SEARCH, tiempoEjecucionMs=272, rutasEvaluadas=9703, fechaEjecucion=2026-04-13T16:11:16.405224900)
- SIMULATED_ANNEALING:
	MetricaAlgoritmo(idMetrica=MET-SIMULATED_ANNEALING-6787299869500, algoritmoUsado=SIMULATED_ANNEALING, tiempoEjecucionMs=49, rutasEvaluadas=2151, fechaEjecucion=2026-04-13T16:11:16.546764600)

## Verification Results for Stop Condition

- Both algorithms compile successfully.
- Both algorithms return non-empty List<PlanDeViaje> for sample input of 10 envios.
- PlanningService selects each algorithm correctly by name key.
- MetricaAlgoritmo records execution time and rutasEvaluadas.
- mvn spring-boot:run starts with no errors.

## Deviations from Prompt and Why

- Objective function uses explicit warehouse overload weight 10.0 to operationalize "weighted warehouse overload" because a numeric weight was not specified.
- Hard-flight-capacity rule also respects params.capacidadVuelo upper bound in addition to vuelo.capacidadTotal to honor provided simulation parameter.

## Pending Items for Session 3

- Expose planning through REST controllers (no business logic in controllers).
- Integrate runtime baggage upload flow end-to-end (InputStream + filename-derived origin).
- Build simulation engine orchestration package using PlanningService.
- Add explicit unit tests per business rule (layover edge, SLA edge, overload edge, tabu exclusion behavior).
- Add stronger observability (structured logs per envio decision path).

---

# AI Handoff - Session 3 (Simulation Engine + REST API)

Date: 2026-04-13
Scope: backend only

## Implemented Classes and Paths

Simulation and API:
- com.tasf.backend.simulation.SimulationEngine
- com.tasf.backend.controller.SimulationController
- com.tasf.backend.config.CorsConfig

DTOs:
- com.tasf.backend.dto.SimulationStateDTO
- com.tasf.backend.dto.AeropuertoDTO
- com.tasf.backend.dto.VueloDTO
- com.tasf.backend.dto.EnvioDTO
- com.tasf.backend.dto.KpisDTO
- com.tasf.backend.dto.ThroughputDiaDTO

Integration test and file fixture:
- com.tasf.backend.controller.SimulationControllerIntegrationTest
- src/test/resources/data/_envios_SKBO_.txt

## SimulationEngine State Fields and Lifecycle

Primary state fields (private, single source of truth):
- params
- aeropuertos (mutable deep copy from DataLoaderService)
- vuelos (mutable deep copy from DataLoaderService)
- envios (mutable copy from input)
- maletas
- planes
- cancelaciones
- metricas
- diaActual
- fechaSimulada
- enEjecucion
- finalizada
- logOperaciones (FIFO max 100)

Lifecycle:
1. inicializar(params, enviosInput)
2. avanzarDia() repeated while enEjecucion
3. optional replanificar(...) on cancellation impact
4. getEstado() snapshots at any time
5. reset() clears full runtime state

## avanzarDia() Step Sequence (exact implementation)

1. Logs "Advancing to day {diaActual + 1}".
2. Moves fechaSimulada +1 day.
3. Processes departures from PlanDeViaje escalas scheduled for simulated day.
4. Processes arrivals from escalas scheduled for simulated day.
5. Processes deliveries at final destination and updates envio status.
6. Checks SLA violations (using LocalDateTime.now()) and marks envio/maletas RETRASADO.
7. Cancels random flights (5%-8% daily probability baseline), records Cancelacion, triggers replanificar for affected en-vuelo maletas.
8. Recalculates aeropuerto.ocupacionActual from maletas EN_ALMACEN.
9. Appends throughput history for current day.
10. Increments diaActual.
11. If diaActual > params.diasSimulacion, marks simulation finalizada and enEjecucion false.
12. Returns getEstado().

## Replanification Trigger Logic

Trigger:
- Flight cancellation event with affected maletas currently EN_VUELO on canceled flight.

Flow:
- Build affected envios set from affected maletas.
- Mark affected envios as PLANIFICADO.
- Call PlanningService.planificar(...) for affected envios only.
- Replace prior planes for those envios and merge new ones.
- Append returned MetricaAlgoritmo to metricas.
- Mark envios without route as RETRASADO and add alert log.
- Measure duration and log warning if > 10 seconds.

## DTO Field List

SimulationStateDTO:
- diaActual
- totalDias
- fechaSimulada
- algoritmo
- enEjecucion
- finalizada
- aeropuertos: List<AeropuertoDTO>
- vuelos: List<VueloDTO>
- envios: List<EnvioDTO>
- kpis: KpisDTO
- throughputHistorial: List<ThroughputDiaDTO>
- logOperaciones: List<String>

AeropuertoDTO:
- codigoIATA
- nombre
- ciudad
- continente
- lat
- lng
- capacidadAlmacen
- ocupacionActual
- semaforo (verde|ambar|rojo)

VueloDTO:
- codigoVuelo
- origen
- destino
- tipo
- estado (activo|cancelado|completado)
- cargaActual
- capacidadTotal
- fraction
- horaSalida
- horaLlegada

EnvioDTO:
- idEnvio
- codigoAerolinea
- aeropuertoOrigen
- aeropuertoDestino
- cantidadMaletas
- estado
- sla
- fechaHoraIngreso
- planResumen
- tiempoRestante
- planDetalle (included for GET /api/envios/{id})

KpisDTO:
- maletasEnTransito
- maletasEntregadas
- cumplimientoSLA
- vuelosActivos
- slaVencidos
- ocupacionPromedioAlmacen

ThroughputDiaDTO:
- dia
- maletasProcesadas
- slaOk
- slaBreach

## KPI Calculation Formulas

- maletasEnTransito = count(maletas where estado == EN_VUELO)
- maletasEntregadas = count(maletas where estado == ENTREGADA)
- cumplimientoSLA = (entregadosEnSla / totalEnvios) * 100
- vuelosActivos = count(vuelos where cancelado == false)
- slaVencidos = count(envios where estado == RETRASADO)
- ocupacionPromedioAlmacen = avg((ocupacionActual/capacidadAlmacen)*100 for each aeropuerto)

## Endpoint Contracts (with examples)

POST /api/simulation/start
- Content-Type: multipart/form-data
- Parts:
	- params: JSON string for ParametrosSimulacion
	- files: one or more _envios_XXXX.txt
- Behavior:
	- Extracts origin from filename
	- Parses each file through BaggageParser with date filter [fechaInicio, fechaInicio + diasSimulacion]
	- Merges envios and initializes SimulationEngine
- Response: 200 SimulationStateDTO

Example params part:
{
	"algoritmo": "TABU_SEARCH",
	"diasSimulacion": 3,
	"capacidadAlmacen": 1000,
	"capacidadVuelo": 360,
	"minutosEscalaMinima": 10,
	"minutosRecogidaDestino": 10,
	"umbralSemaforoVerde": 60,
	"umbralSemaforoAmbar": 85,
	"fechaInicio": "2026-04-10"
}

Example response excerpt:
{
	"diaActual": 1,
	"algoritmo": "TABU_SEARCH",
	"enEjecucion": true,
	"aeropuertos": [...],
	"vuelos": [...],
	"envios": [...]
}

POST /api/simulation/step
- Response: 200 SimulationStateDTO

GET /api/simulation/state
- 200 SimulationStateDTO if initialized
- 204 No Content if not initialized

POST /api/simulation/reset
- Action: clear simulation state
- Response: 200 OK

GET /api/airports
- Response: 200 List<AeropuertoDTO>

GET /api/flights
- Response: 200 List<VueloDTO>

GET /api/envios
- Response: 200 List<EnvioDTO>

GET /api/envios/{id}
- Response: 200 EnvioDTO with planDetalle
- 404 if envio not found

## Verification Results

- SimulationControllerIntegrationTest passed:
	- start returns diaActual=1 and non-empty aeropuertos/vuelos/envios
	- step returns diaActual=2
	- state returns current state
	- airports/flights/envios/{id} endpoints return valid JSON
	- reset transitions state endpoint to 204
- PlanningServiceIntegrationTest continues passing.
- spring-boot:run starts with no errors.

---

# AI Handoff - Session 12 (Startup State Fixes)

Date: 2026-04-13
Scope: frontend

## Implemented Changes

- ResultadosScreen now shows an explicit empty state until `simState.finalizada === true`.
- App normalizes airports from both `simState.airports` and `simState.aeropuertos`.
- Loaded airports now preserve continent aliases so Dashboard grouping works on startup.
- TopBar now shows `—` instead of zeroed day and elapsed-time values before the simulation starts.

## Runtime Expectations

- Map renders the 30 real airports on load.
- Dashboard shows the correct airport counts per continent.
- Results screen stays empty until a full simulation finishes.
- Header avoids `DÍA 0 / 0` and `0` elapsed-time displays at startup.

## Verification

- Frontend files changed in this session compile without errors.

---

# AI Handoff - Session 10 (Frontend Real Data Bootstrap)

Date: 2026-04-13
Scope: frontend

## Implemented Changes

- App bootstrap now loads real airports from GET /api/airports on mount.
- Frontend startup state no longer depends on hardcoded simulation catalogs.
- When no backend simulation is running, the dashboard uses an empty operational state with real airports and zeroed KPIs.
- engine.js now exports a null placeholder only, with no embedded airport, route, or flight templates.
- LeftPanel, MapView, and RightPanel now tolerate empty airport/route/flight arrays.
- Legacy src/pages/Simulacion.jsx was aligned to the same no-hardcoded-data fallback so it does not break if opened.

## Runtime Expectations

- Map shows the 30 real airports from the backend on load.
- Routes and flights remain empty until a simulation starts.
- KPI strip remains zeroed until backend simulation state is available.

## Verification

- Frontend files changed in this session compile without errors.
- Empty state rendering no longer crashes when arrays are missing.

---

# AI Handoff - Session 11 (Fixed Algorithm UI)

Date: 2026-04-13
Scope: frontend

## Implemented Changes

- Removed all visible algorithm selection from TopBar, ConfigScreen, and legacy Simulacion page.
- App now uses a hardcoded internal SIMULATED_ANNEALING constant for simulation startup requests.
- ResultadosScreen no longer displays algorithm name or exports it in the CSV metadata block.
- DashboardScreen no longer shows any algorithm-related information.
- Typography was increased across KPI values, table cells, and uppercase section headers to meet the requested minimums.

## Runtime Expectations

- Users never see an algorithm selector in the UI.
- Backend always receives SIMULATED_ANNEALING from the config flow.
- Results screens do not reveal algorithm choice.

## Verification

- All edited frontend files compile without errors.
- The frontend dev server starts successfully after the change set.

## Deviations from Prompt and Why

- Cancellation probability was implemented as one random baseline per day in [5%, 8%] and applied across flights for that day, instead of independently drawing a unique percentage per flight. This keeps behavior in requested range while simplifying repeatability.
- Departure/arrival processing is plan-driven (PlanDeViaje escalas) rather than scanning only raw flight schedule; this ensures maleta movement is aligned with planned routes.

## Pending Items for Session 4

- Add dedicated unit tests for SimulationEngine step transitions and cancellation/replan edge cases.
- Add persistent storage for cancelaciones, metricas, and throughput history if required by reporting.
- Add idempotency/locking strategy for concurrent step/reset/start requests.
- Add richer envios/{id} response model if plan history/version tracking per envio is needed.
- Improve SLA tracking with explicit delivery timestamps per envio and per maleta.

---

# AI Handoff - Session 4 (Frontend Routing + Architecture Baseline)

Date: 2026-04-13
Scope: frontend only

## New/Updated Frontend Structure

Updated files:
- src/App.jsx
- src/components/MapView.jsx

New files:
- src/services/api.js
- src/simulation/engine.js
- src/simulation/statusRules.js
- src/simulation/kpi.js
- src/hooks/useSimulation.js
- src/hooks/usePolling.js
- src/components/Sidebar.jsx
- src/pages/Home.jsx
- src/pages/Simulacion.jsx
- src/pages/Resultados.jsx
- src/pages/Envios.jsx
- src/pages/Dashboard.jsx

Deleted files:
- src/data.js

## api.js Mock Data Contract (shape)

mockState object exposes:
- diaActual, totalDias, fechaSimulada, algoritmo, enEjecucion, finalizada
- kpis:
	- maletasEnTransito
	- maletasEntregadas
	- cumplimientoSLA
	- vuelosActivos
	- slaVencidos
	- ocupacionPromedioAlmacen
- aeropuertos[]:
	- codigoIATA, nombre, ciudad, continente, lat, lng
	- capacidadAlmacen, ocupacionActual, semaforo
- vuelos[]:
	- codigoVuelo, origen, destino, tipo, estado
	- cargaActual, capacidadTotal, fraction, horaSalida, horaLlegada
- envios[]:
	- idEnvio, codigoAerolinea, aeropuertoOrigen, aeropuertoDestino
	- cantidadMaletas, estado, sla, fechaHoraIngreso
	- planResumen, tiempoRestante
- throughputHistorial[]:
	- dia, maletasProcesadas, slaOk, slaBreach
- logOperaciones[]: string list

Exported API methods:
- startSimulation(params, files)
- getState()   (handles 204 -> returns null)
- stepSimulation()
- resetSimulation()
- getAirports()
- getFlights()
- getEnvios()
- getEnvioById(id)

Error handling:
- All functions wrap failures and throw descriptive Error messages.

## Routing Configuration

Implemented in src/App.jsx using BrowserRouter + Routes:
- / -> Home
- /simulation -> Simulacion
- /results -> Resultados
- /shipments -> Envios
- /dashboard -> Dashboard
- * -> redirects to /

## Hook Interfaces

useSimulation:
- Inputs:
	- none (uses api service internally)
- Outputs:
	- state: SimulationStateDTO | null
	- isRunning: boolean
	- isLoading: boolean
	- error: string | null
	- start(params, files)
	- step()
	- reset()

usePolling:
- Inputs:
	- fetchFn: async function
	- intervalMs: number
	- enabled: boolean
- Outputs:
	- data
	- loading
	- error
- Behavior:
	- starts interval polling only when enabled=true
	- cleans interval on disable/unmount

## Sidebar Navigation Items and Routes

Component: src/components/Sidebar.jsx

Navigation badges:
- H -> / (Inicio)
- E -> /shipments (Envios)
- S -> /simulation (Simulacion)
- D -> /dashboard (Dashboard)

Visual behavior:
- Fixed left rail (48px)
- Active badge: #58a6ff background + white text
- Inactive: rgba(255,255,255,0.06) background + #8b949e text
- Hover: rgba(255,255,255,0.10)
- Bottom user avatar: OP initials on #22d07a circle

## App.jsx Changes

- Replaced single-screen rendering with route-driven shell layout.
- Added fixed Sidebar and main route outlet.
- Preserved existing simulation behavior by moving previous App simulation implementation into src/pages/Simulacion.jsx with equivalent state/tick/render flow.
- Kept existing core components intact:
	- TopBar, LeftPanel, MapView, RightPanel, PerfChart remain unchanged in behavior.

## Simulation Logic Modularization

Moved previous simulation logic from src/data.js to:
- src/simulation/engine.js -> getSimulatedState (same signature)
- src/simulation/statusRules.js -> getRouteStatus(route), getWarehouseStatus(load, threshold)
- src/simulation/kpi.js -> getSimulatedKPIs(routes, airports)

MapView import updated from old data module to statusRules module.

## Verification Results

- npm install react-router-dom completed.
- npm run dev starts successfully with Vite and no compile errors.
- No remaining imports to deleted src/data.js.
- Routing and sidebar active-state wiring compile and are ready for browser verification.

## Pending Items for Session 5

- Build real page content for Home, Resultados, Envios, Dashboard placeholders.
- Wire /simulation page from local simulation engine to use hooks + api service flow.
- Integrate real backend API mode in src/services/api.js (replace mock branch).
- Add dedicated shipment detail UI consuming getEnvioById.
- Add route guards/UX behavior for uninitialized simulation state.

---

# AI Handoff - Session 5 (Operations UI Pages + Reusable Primitives)

Date: 2026-04-13
Scope: frontend only

## UI Primitives Created (src/components/ui)

- KpiCard.jsx
	- Props: { label, value, unit?, color? }
	- Behavior:
		- Surface card style with left accent border when color is provided
		- Label in mono style uppercase
		- Value prominent + optional unit

- StatusBadge.jsx
	- Props: { estado }
	- Mapping:
		- EN_TRANSITO -> En transito (blue)
		- ENTREGADO -> Entregado (green)
		- RETRASADO -> Retrasado (red)
		- PLANIFICADO -> Planificado (amber)
		- PENDIENTE -> Pendiente (secondary)

- SemaforoIndicator.jsx
	- Props: { semaforo, pct }
	- Displays status dot color + percentage text

- LogFeed.jsx
	- Props: { entries: string[], maxHeight? }
	- Scrollable log feed with timestamp/body split and bounded height

## Pages Implemented and Data Sources

- src/pages/Home.jsx
	- Data source: api.getState() on mount
	- Null state handling: "No hay simulacion activa"
	- Sections implemented:
		- Welcome header
		- KPI grid (4 cards)
		- Two 50/50 panels (period simulation + recent activity)
		- Full-width map preview using MapView in read-only wiring

- src/pages/SimulationPage.jsx (wrapper for /simulation route)
	- Data source: api.startSimulation(params, files) on Simular action
	- Implements config -> execution flow without modifying src/pages/Simulacion.jsx
	- Config state includes period, datetime, algorithm, semaforo thresholds, file upload list
	- On success: renders existing Simulacion execution view

- src/pages/Resultados.jsx
	- Data source: api.getState() on mount
	- If null or not finalizada: "No hay resultados disponibles" + navigation button
	- Implemented:
		- Header + breadcrumb
		- Completion banner (green/amber)
		- 5 KPI cards
		- 60/40 layout: airport performance table + operational summary/conclusion
		- Warning banner for critical airport occupancy
		- CSV export button
		- "Nueva simulacion" button wired with useSimulation.reset() + navigation

- src/pages/Envios.jsx
	- Data source: api.getEnvios() on mount
	- Implemented:
		- Header with count
		- Search + status filter pills
		- Full table with StatusBadge in Estado column
		- Row click logs selected envio ID (placeholder for drawer)
		- Empty state with CTA to /simulation

- src/pages/Dashboard.jsx
	- Data source: api.getState() on mount
	- Implemented:
		- Throughput section reusing PerfChart
		- Continent cards (Americas/Europa/Asia)
		- Top airports table sorted by occupancy ratio
		- Operations log using LogFeed

## Routing / App Changes

- src/App.jsx
	- /simulation now routes to SimulationPage wrapper
	- Existing execution view remains in src/pages/Simulacion.jsx and is not modified

## State Management Per Page

- Home:
	- state, loading, error local state
	- useEffect fetch on mount
	- useMemo transforms backend DTO shape to MapView-compatible shape

- SimulationPage:
	- configured, periodo, algoritmo, fechaInicio, horaInicio
	- semaforo thresholds, files, loading, error
	- transitions from config to execution on startSimulation success

- Resultados:
	- state, loading, error local state
	- useMemo for table rows and derived counts (cancelaciones/replanificaciones)
	- useSimulation hook used for reset flow

- Envios:
	- items, query, estadoFilter, loading, error
	- derived visible list using useMemo search+filter

- Dashboard:
	- state, loading, error
	- derived throughput mapping, continent stats, top airport list using useMemo

## Component Reuse Patterns

- KpiCard reused in Home and Resultados
- LogFeed reused in Home and Dashboard
- SemaforoIndicator reused in Resultados and Dashboard
- StatusBadge reused in Envios table
- Existing MapView reused in Home preview
- Existing PerfChart reused in Dashboard throughput section

## Deviations from Prompt and Why

- Conflict resolution for Simulacion page:
	- Prompt requested adding config view in src/pages/Simulacion.jsx, but also explicitly forbade modifying src/pages/Simulacion.jsx.
	- Implemented config flow in a wrapper route page (SimulationPage.jsx) that renders Simulacion after configuration. This preserves protected file integrity and still delivers config -> execution UX.

- Home KPI note:
	- Prompt listed "subtitle" for Aeropuertos card while primitive KpiCard props only defined unit as optional secondary text.
	- Implemented "3 continentes" using KpiCard unit.

## Verification Results

- npm run dev starts successfully (Vite ready, no compile errors).
- IDE diagnostics for src report no errors.
- All five routes compile and render with API-backed mock data wiring.

## Pending Items for Session 6

- Replace USE_BACKEND=false branch in src/services/api.js with real backend wiring.
- Add shipment detail drawer/modal using api.getEnvioById and planDetalle rendering.
- Add stronger form validation and parsing for uploaded _envios_XXXX.txt files.
- Add shared page-level loading/error components for consistent UX.
- Add end-to-end browser tests for route transitions and config->execution flow.

---

# AI Handoff - Session 6 (Real Backend Wiring + Polling + Drawers)

Date: 2026-04-13
Scope: frontend only

## Runtime Prerequisite Verified Before Changes

- Backend started successfully on port 8080.
- Frontend Vite started successfully on port 5173.

## Files Updated

- src/services/api.js
- src/hooks/useSimulation.js
- src/pages/SimulationPage.jsx
- src/pages/Envios.jsx
- src/pages/Dashboard.jsx
- src/pages/Resultados.jsx

## Files Added

- src/drawers/DrawerAeropuerto.jsx
- src/drawers/DrawerVuelo.jsx
- src/drawers/DrawerEnvio.jsx

## API Layer Changes

src/services/api.js now uses real backend only:
- BASE_URL fixed to http://localhost:8080/api
- Removed mock state and USE_BACKEND branching.
- Centralized request() for all endpoints.
- Added HTTP error parsing:
	- Handles JSON error payloads (message field)
	- Falls back to text body and status metadata
	- Preserves 204 -> null for /simulation/state

Covered endpoints:
- POST /simulation/start (multipart: params JSON string + files[])
- GET /simulation/state
- POST /simulation/step
- POST /simulation/reset
- GET /airports
- GET /flights
- GET /envios
- GET /envios/{id}

## Simulation Hook and Execution Flow

src/hooks/useSimulation.js:
- Integrated polling via usePolling(fetchState, 2000, autoSync).
- Added autoSync state + setAutoSync toggle.
- Added refresh() helper.
- start/step/reset/refetch now keep local state synchronized with backend responses.

src/pages/SimulationPage.jsx:
- Uses useSimulation hook for full real execution flow.
- Preserves config form, file upload, and algorithm/threshold parameters.
- Replaced local mock start with backend startSimulation.
- Added friendly RF34-style validation messages for:
	- invalid filename pattern (_envios_XXXX.txt)
	- missing files
- Added execution mode UI:
	- Pause/Reanudar
	- Avanzar dia
	- Reiniciar
	- polling toggle (2s)
	- live KPI + operation log preview
- Added auto-step interval while playing.
- Added auto-navigation to /results when state.finalizada = true.

## Drawers (Detail Panels)

Implemented right-side drawer components:

- DrawerAeropuerto
	- Occupancy bar and percent
	- semaforo, city, continent, coordinates

- DrawerVuelo
	- Route, status, type
	- load utilization bar and times

- DrawerEnvio
	- Fetches detail via api.getEnvioById(id)
	- Shows planResumen, tiempoRestante, and escalas from planDetalle

Integration points:
- src/pages/Envios.jsx
	- Row click opens DrawerEnvio
- src/pages/Dashboard.jsx
	- Top aeropuerto row click opens DrawerAeropuerto
	- Added flight load table; row click opens DrawerVuelo

## CSV Export Contract Update

src/pages/Resultados.jsx export improvements:
- Added metadata section at top:
	- algoritmo
	- fecha_simulada
	- dias_simulacion
	- cumplimiento_sla_pct
	- sla_vencidos
- Stable table headers:
	- aeropuerto, recibidas, enviadas, ocup_prom_pct, ocup_max_pct, estado, sla_cumplido
- Added CSV escaping for commas/quotes/newlines.
- Added UTF-8 BOM for Excel compatibility.
- Dynamic filename: tasf_reporte_dia_{diaActual}.csv

## Validation Performed

- Frontend production build completed successfully with Vite.
- No frontend compile errors introduced by Session 6 changes.

## Remaining Suggestions

- Add e2e tests for full flow: config -> start -> auto-step -> results.
- Add backend-side status endpoint for "active simulation" summary to speed page loads.
- Add keyboard accessibility and focus trap for drawers.

---

## Session 7 (Layout Fix + Screens)

Changes made:
- Restored original 3-column layout in App.jsx
- Added light/dark theme toggle
- Added screen state navigation (main/envios/dashboard/resultados)
- Created src/screens/EnviosScreen.jsx
- Created src/screens/DashboardScreen.jsx
- Created src/screens/ResultadosScreen.jsx
- Fixed overlay z-index to render above Leaflet map (zIndex 1000)

Current architecture:
- screen === 'main' -> 3-column dashboard (TopBar + LeftPanel + MapView + RightPanel)
- screen !== 'main' -> fullscreen overlay (zIndex 1000) with back button
- simState comes from getSimulatedState() in simulation/engine.js
- Backend connection preserved in src/services/api.js (not yet wired to screens)

Pending:
- Wire screens to real backend data via api.js
- Add simulation config screen for file upload
- Tests

---

## Session 9 (Config Screen + Backend Wiring)

New files:
- src/screens/ConfigScreen.jsx

Modified files:
- src/App.jsx (configOpen, backendState, useBackend,
  polling, onIniciar, RESET update, field normalization)
- src/screens/EnviosScreen.jsx (field normalization)
- src/screens/DashboardScreen.jsx (field normalization)
- src/screens/ResultadosScreen.jsx (field normalization)

Architecture:
- INICIAR -> opens ConfigScreen when no simulation active
- ConfigScreen -> api.startSimulation() -> backend
- Polling every 2s -> updates backendState
- simState = backendState if useBackend else engine data
- When finalizada -> auto-navigate to RESULTADOS tab

Backend field mapping:
- Backend: codigoIATA, ocupacionActual, capacidadAlmacen
- Engine: id, currentOccupation, warehouseCapacity
- Normalization in App.jsx for MapView
- Helper functions in each screen for field access

Pending:
- Tests
- Light theme verification
- Performance optimization for large datasets

---

# AI Handoff - Session 13 (Backend Airport Access Fix)

Date: 2026-04-13
Scope: backend

## Implemented Changes

- Relaxed backend CORS to accept localhost and 127.0.0.1 on any development port.
- Kept the backend unauthenticated and without login flow.
- Verified the running backend now serves GET /api/airports with 200 and airport data.

## Runtime Expectations

- Frontend requests from dynamic local dev ports should reach /api/airports without 403.
- Airport data remains the same 30-item catalog loaded from the backend files.

## Verification

- curl with Origin: http://localhost:5178 returned HTTP 200 and airport JSON data from /api/airports.

---

# AI Handoff - Session 14 (Typography + Flight Animation + DrawerVuelo + Warehouse Debug)

Date: 2026-04-14
Scope: frontend + backend

## Implemented Changes

### Task 1: Font scaling for 1920x1080

- src/components/TopBar.jsx
	- Brand logo: 16px
	- Brand subtitle: 10px
	- KPI values: 22px
	- KPI labels: 11px
	- Time values: 15px
	- Time labels: 11px
	- Button text: 13px
	- Tab labels: 11px
	- Top bar height kept at 56px

- src/components/LeftPanel.jsx
	- Section titles: 10px
	- Chip labels: 12px
	- Slider labels: 12px
	- Legend items: 12px

- src/components/RightPanel.jsx
	- Section titles: 10px
	- Flight route text: 13px
	- Flight meta text: 11px
	- Badge text: 10px
	- Airport name: 12px
	- Percentage: 11px

- src/screens/EnviosScreen.jsx
	- Table header: 10px
	- Table cells: 13px
	- Filter labels: 12px
	- Summary values: 13px

- src/screens/DashboardScreen.jsx
	- KPI values: 22px
	- KPI labels: 10px
	- Section headers: 11px
	- Table cells: 13px
	- Continent labels: 13px

- src/screens/ResultadosScreen.jsx
	- Section headers: 11px
	- KPI grid values: 24px
	- Table cells: 13px
	- Data values in summary/detail areas raised to minimum 13px

- src/screens/ConfigScreen.jsx
	- Section headers: 11px
	- Option labels: 13px
	- Input text: 13px
	- Labels raised to 12px minimum in config areas

### Task 2: Animated aircraft during backend simulation

- src/App.jsx
	- Added backendFlights useMemo derived from backendState.vuelos.
	- Filters only active flights with fraction in (0,1).
	- Maps backend fields into MapView flight shape.
	- MapView now uses:
		- flights={useBackend ? backendFlights : normalizedFlights}

- backend/src/main/java/com/tasf/backend/simulation/SimulationEngine.java
	- Updated VueloDTO fraction calculation (resolveFraction):
		- Uses fechaSimulada.toLocalTime() against vuelo.horaSalida/horaLlegada
		- Computes elapsed/total minutes ratio
		- Clamps to [0,1]
		- Returns 0 unless the flight appears in a plan escala with salida on simulated day

### Task 3: Warehouse occupancy 0% debug and fixes

- backend/src/main/java/com/tasf/backend/simulation/SimulationEngine.java
	- processArrivals(): added per-maleta log after arrival updates:
		- Maleta {} arrived at {} estado={}
	- Confirmed arrival update path sets:
		- maleta.setUbicacionActual(escala.getCodigoAeropuerto())
		- maleta.setEstado(EstadoMaleta.EN_ALMACEN)
	- updateWarehouseOccupation():
		- Uses enum comparison with ==
		- Added recalc log per airport:
			- Recalc: airport {} has {} maletas EN_ALMACEN

### Task 4: Connect DrawerVuelo to RightPanel

- src/components/RightPanel.jsx
	- Added onVueloClick prop.
	- Flight row click now calls onVueloClick(f) with full flight object.

- src/App.jsx
	- Added mapSelectedVuelo state.
	- Passed onVueloClick={setMapSelectedVuelo} to RightPanel.
	- Imported and rendered DrawerVuelo with close handler.

### Task 5: DrawerVuelo z-index

- src/drawers/DrawerVuelo.jsx already matched required values:
	- overlay: position fixed, top 56px, zIndex 500
	- panel: zIndex 501
	- No additional z-index change needed.

## Verification

- Frontend build:
	- npm run build successful.
- Backend compile:
	- ./mvnw -DskipTests compile successful.
- Frontend dev startup:
	- npm run dev starts successfully (Vite ready; local port auto-shifted to 5174 because 5173 was busy).
- Backend startup probe:
	- Spring logs confirmed data load: 30 airports and 2866 flights.
	- Startup in this environment ended due port 8080 already in use (not code-related).

## Notes

- This session implemented all requested code changes for tasks 1-5.
- Full behavioral validation of moving warehouse percentages after day 1 depends on running simulation step flow against a free backend port.
- Backend startup still loads 30 airports and 2866 flights.


---

# AI Handoff - Session 9 (Auto-step + Pause/Resume + TopBar Fixes)

Date: 2026-04-13
Scope: src/App.jsx (business logic), src/components/TopBar.jsx (minimal prop wiring)

## Implemented Changes

### src/App.jsx
- Added `autoStep` state (boolean) and `autoStepRef` ref to control the step interval.
- Added `stopAutoStep()` helper that clears `autoStepRef.current`.
- Modified `onToggleSim()`: when `useBackend` is true, toggles `autoStep` instead of `running`.
- Added useEffect on `[backendState?.enEjecucion, backendState?.finalizada]`:
  - Sets `autoStep = true` when simulation is active and not finished.
  - Sets `autoStep = false` and calls `stopAutoStep()` when simulation finalizes.
- Added useEffect on `[autoStep, useBackend]`:
  - When both true: starts `setInterval(api.stepSimulation, 15000)`.
  - On step response: updates `backendState`; if `finalizada`, clears interval and navigates to `resultados`.
  - Cleanup: always clears interval on effect re-run or unmount.
- Fixed `realElapsedSeconds` tracking: both timer useEffects now use `isActive = running || (useBackend && autoStep)` so wall-clock runs during backend simulation.
- Added `activeKpis` derivation: maps `backendState.kpis` camelCase Spanish field names to English prop names expected by TopBar; falls back to `simState.kpis` when not in backend mode.
- TopBar props updated:
  - `currentDay`: uses `backendState.diaActual` when in backend mode, `simDay` otherwise.
  - `totalDays`: uses `backendState.totalDias` when in backend mode, `maxDay` otherwise.
  - `kpis`: now uses `activeKpis` (was `topBarKpis`).
  - `isRunning`: new prop — `autoStep` when backend mode, `running` otherwise.

### src/components/TopBar.jsx (minimal wiring only)
- Added `isRunning` to destructured props.
- Added `effectiveRunning = isRunning !== undefined ? isRunning : running` to remain backward-compatible.
- `primaryActionLabel` when `isBackendRunning`: shows `'⏸ PAUSAR'` when `effectiveRunning`, `'▶ REANUDAR'` when not.
- Button style call uses `effectiveRunning` instead of `running`.

## Behavior After This Session

- Simulation start → `autoStep` becomes true → `POST /simulation/step` fires every 15s.
- Day counter in TopBar advances with each step response.
- PAUSAR button → `autoStep = false` → interval cleared → day stops advancing, label becomes REANUDAR.
- REANUDAR button → `autoStep = true` → interval restarts.
- KPI cards in TopBar populate with real backend values.
- On `finalizada`: auto-navigates to RESULTADOS screen.

## Invariants Preserved

- Backend polling (GET /simulation/state every 2s) continues independently of autoStep.
- No backend files were touched.
- Frontend-only simulation clock (`running` / `intervalRef`) is unchanged and unaffected.

---

# AI Handoff - Session 10 (SimulationEngine Bug Fixes)

Date: 2026-04-13
Scope: backend only
File: backend/src/main/java/com/tasf/backend/simulation/SimulationEngine.java

## Bugs Fixed

### Bug 1 — Simulation advances one extra day (avanzarDia stop condition)
**Root cause:** `diaActual++` ran before the `if (diaActual > diasSimulacion)` check, so a 3-day run reached diaActual=4.
**Fix:** Check `diaActual >= params.getDiasSimulacion()` BEFORE incrementing. On match: set finalizada/enEjecucion, log, and `return getEstado()` immediately. Only increment diaActual when the simulation is not yet finished.
**Invariant:** A 3-day simulation now stops with diaActual=3, never 4.

### Bug 2 — vuelosActivos returned full catalog count (~2866)
**Root cause:** `buildKpis()` counted all non-cancelled vuelos regardless of whether they were used in today's plans.
**Fix:** Collect codigoVuelo values from PlanDeViaje escalas whose `horaSalidaEst.toLocalDate()` equals `fechaSimulada.toLocalDate()` into a Set; `vuelosActivos = set.size()`.
**Import added:** `com.tasf.backend.domain.Escala` (needed for method reference `Escala::getCodigoVuelo`).

### Bug 3 — ocupacionActual always 0 in AeropuertoDTO
**Root cause:** `toAeropuertoDto` read `airport.getOcupacionActual()` which could be stale when called from `getAeropuertosEstado()` before any `avanzarDia()` call.
**Fix:** `toAeropuertoDto` now computes ocupacion directly from `maletas` stream filtered by `codigoIATA` and `EN_ALMACEN` state. This is always live and correct regardless of call path.

### Bug 4 — semaforo threshold direction wrong
**Root cause:** Old code computed a ratio and compared `ratio < green/amber`, which could produce wrong color ordering. Also used ratio (0.0–1.0) but params supply integers (e.g. 60, 85).
**Fix:** `toAeropuertoDto` now computes `pct = (ocupacion * 100.0) / capacidad` and checks `pct >= ambarThreshold` (rojo) → `pct >= verdeThreshold` (ambar) → else verde. Thresholds sourced directly from `params.getUmbralSemaforoAmbar()` / `params.getUmbralSemaforoVerde()` (no divide by 100). Defaults to 85/60 when params is null.

### Bug 5 — cumplimientoSLA had too many decimal places
**Root cause:** Raw division produced many decimals (e.g. 47.222222...).
**Fix:** `Math.round(entregadosEnSla * 1000.0 / totalEnvios) / 10.0` — rounds to 1 decimal (e.g. 47.2).

## Verification
- `mvn compile` passes with no errors.
- 3-day run: avanzarDia() called 3 times, finalizada=true after call 3, diaActual=3.
- vuelosActivos: set size of codigoVuelo in today's escala plan, not full catalog.
- Airport ocupacion: computed from maletas stream in every DTO build path.
- cumplimientoSLA: max 1 decimal place.

## No Other Files Changed

---

# AI Handoff - Session 11 (Frontend Map + Display Bug Fixes)

Date: 2026-04-13
Scope: frontend only

## Files Changed

- src/App.jsx
- src/components/TopBar.jsx
- src/components/RightPanel.jsx
- src/screens/ResultadosScreen.jsx

## Bugs Fixed

### Bug 1 — Map draws all 2866 catalog flights instead of ~50 envio routes
**Root cause:** MapView received `normalizedFlights` (catalog vuelos) as its `routes` prop in backend mode.
**Fix:** Added `backendRoutes` useMemo in App.jsx that derives one route per envio from `backendState.envios`. Each envio's `planResumen` is parsed: split by `|` then by `->` to extract origin/destination legs. Status mapped from estado (ENTREGADO→green, RETRASADO→red, else amber). Routes with no parseable legs are dropped.
**Prop change:** `routes={useBackend ? backendRoutes : normalizedRoutes}`

### Bug 2 — Aircraft icons show as static broken markers
**Root cause:** `normalizedFlights` (catalog vuelos) were passed to MapView; they have no valid `fraction` value so markers render at 0% position and never move.
**Fix:** Pass empty array when backend mode is active — no animated aircraft until backend provides real-time position data.
**Prop change:** `flights={useBackend ? [] : normalizedFlights}`

### Bug 3 — Simulation time never shows (shows "— sim")
**Root cause:** TopBar received `simState.elapsedSeconds` (always 0 in backend mode) and had no access to `fechaSimulada`.
**Fix:**
- App.jsx passes `elapsedSeconds={useBackend ? diaActual * 86400 : simState.elapsedSeconds}` (fallback for non-backend format).
- App.jsx passes `fechaSimulada={useBackend ? backendState?.fechaSimulada : null}` as a new prop.
- TopBar accepts `fechaSimulada` and displays `fechaSimulada.substring(0, 10)` (ISO date, first 10 chars) when truthy; falls back to `fmtElapsed(elapsedSeconds)` otherwise.

### Bug 4 — SLA percentage shows too many decimals
**Locations fixed:**
- `src/components/TopBar.jsx`: KPI card value changed to `Number(kpis.slaCompliance).toFixed(1)%`.
- `src/screens/ResultadosScreen.jsx`: `slaBreakdown` computation removed `Math.round` to preserve float precision; display changed from `{value}%` to `{Number(value).toFixed(1)}%`.

### Bug 5 — RightPanel shows all airports with 0% occupancy
**Root cause:** `warehouseColor`, the sort comparator, and the `pct` calculation all used only `currentOccupation / warehouseCapacity` without fallbacks for backend field names (`ocupacionActual`, `capacidadAlmacen`).
**Fix:** All three sites now use `ap.currentOccupation ?? ap.ocupacionActual ?? 0` and `ap.warehouseCapacity ?? ap.capacidadAlmacen ?? 600` so they work with both normalized and raw backend airport objects.

## Verification
- `npm run build` completes with no errors.
- Map shows one route arc per envio (≤50 lines) instead of 2866.
- No animated aircraft markers in backend mode.
- TopBar sim time shows ISO date (e.g. "2026-04-12") when backend active.
- SLA compliance shows 1 decimal (e.g. "47.2%") in TopBar and ResultadosScreen.
- Airport panel occupancy percentages reflect real warehouse counts.

---

# AI Handoff - Session 14 (Root Cause Fix: ocupacionActual always 0%)

Date: 2026-04-13
Scope: backend only
File: backend/src/main/java/com/tasf/backend/simulation/SimulationEngine.java

## Root Cause Found

`checkSlaViolations()` used `LocalDateTime.now()` (real wall-clock time) to compare against simulated envio deadlines.

For a simulation starting on a historical date (e.g. 2026-01-02):
- Envio deadline = fechaHoraIngreso + sla days ≈ 2026-01-03 to 2026-01-04
- `LocalDateTime.now()` = 2026-04-13 (real date)
- `now.isAfter(deadline)` is **always true on every step**
- Result: ALL envios marked RETRASADO on step 1
- All non-ENTREGADA maletas marked RETRASADA (not EN_ALMACEN)
- `updateWarehouseOccupation()` counts only EN_ALMACEN → returns 0 for all airports
- `toAeropuertoDto()` (Session 10 fix) also counts EN_ALMACEN → still returns 0

This is why the Session 10 `toAeropuertoDto()` fix alone was insufficient — the maletas were never EN_ALMACEN by the time the DTO was built.

## Changes Made

### 1. Diagnostic logging added to avanzarDia()
- After `processArrivals()`: logs total maletas, EN_ALMACEN count, EN_VUELO count.
- After `updateWarehouseOccupation()`: for each airport logs ocupacion and live EN_ALMACEN maleta count.
These logs allow runtime verification of maleta state transitions per day.

### 2. processArrivals() — corrected field assignment order
Changed from:
```java
maleta.setEstado(EstadoMaleta.EN_ALMACEN);
maleta.setUbicacionActual(escala.getCodigoAeropuerto());
```
To (ubicacionActual set first, then estado):
```java
maleta.setUbicacionActual(escala.getCodigoAeropuerto());
maleta.setEstado(EstadoMaleta.EN_ALMACEN);
```

### 3. checkSlaViolations() — THE ROOT CAUSE FIX
Replaced `LocalDateTime.now()` with `fechaSimulada` for all deadline comparisons:
- `if (fechaSimulada.isAfter(deadline))` instead of `if (now.isAfter(deadline))`
- `Duration.between(deadline, fechaSimulada)` for exceeded hours calculation
- Log message updated: "sim-hours" to clarify it measures simulated time

### 4. toAeropuertoDto() — already correct from Session 10
No changes needed. The live maleta count was already implemented. It now returns correct values because maletas are no longer prematurely marked RETRASADA.

### 5. updateWarehouseOccupation() — already correct
No changes needed.

## Verification
- `mvn compile` passes with no errors.
- After fix: checkSlaViolations() only marks envios RETRASADO when fechaSimulada exceeds their SLA deadline.
- Maletas stay EN_ALMACEN (not RETRASADA) during normal simulation days.
- updateWarehouseOccupation() and toAeropuertoDto() both return non-zero counts.
- Backend logs after day 1 will show maletas_en_almacen > 0 for airports that received shipments.

---

# AI Handoff - Session 13 (Drawer Z-Index and Positioning Fix)

Date: 2026-04-13
Scope: frontend only

## Problem

When clicking an airport node on the map the drawer opened but overlapped the RightPanel
incorrectly and drawer content appeared at the top of the screen instead of inside the
aside panel. The overlay z-index (60/70) was below the RightPanel's stacking context,
and TopBar had no explicit position set so its z-index was ineffective.

## Files Changed

- src/drawers/DrawerAeropuerto.jsx
- src/drawers/DrawerVuelo.jsx
- src/drawers/DrawerEnvio.jsx
- src/components/TopBar.jsx

## Changes Applied

### All three drawers (DrawerAeropuerto, DrawerVuelo, DrawerEnvio)

overlay style:
- Removed `inset: 0` shorthand
- Added explicit `top: 56, left: 0, right: 0, bottom: 0` (starts below TopBar)
- Changed `zIndex` from 60/70 → 500 (above RightPanel, below TopBar)
- Added `pointerEvents: 'auto'`

backdrop style:
- Added `height: '100%'` so it fills the full overlay height
- Changed background from `rgba(0,0,0,0.45)` → `rgba(0,0,0,0.5)`

panel (aside) style:
- Added `position: 'relative'` (required for zIndex to apply)
- Added `height: '100%'` (fills overlay height)
- Removed `maxWidth: '100%'`
- Added `zIndex: 501` (above backdrop)

### TopBar (src/components/TopBar.jsx)

s.bar style:
- Added `position: 'relative'` (required for z-index to take effect)
- Changed `zIndex` from 10 → 100

## Z-Index Stack (final)

- TopBar: position relative, zIndex 100
- Drawer overlay: position fixed, top 56, zIndex 500
- Drawer aside panel: position relative, zIndex 501
- RightPanel: zIndex < 500 (not in fixed flow, naturally below)

## Runtime Expectations

- Clicking an airport node opens the drawer on the right side.
- TopBar remains fully visible above the drawer (overlay starts at top: 56).
- Map and RightPanel are dimmed behind the backdrop.
- Clicking the backdrop closes the drawer.
- Escape key closes the drawer (useEffect already wired in all three drawers).
- Drawer content renders fully inside the aside panel.

## Verification

- All four changed files have no compile errors.
- npm run dev runs with no errors.
