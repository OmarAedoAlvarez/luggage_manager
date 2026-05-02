package com.tasf.backend.simulation;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Cancelacion;
import com.tasf.backend.domain.Escala;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.EstadoMaleta;
import com.tasf.backend.domain.Maleta;
import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.PlanningResult;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.dto.AeropuertoDTO;
import com.tasf.backend.dto.EnvioDTO;
import com.tasf.backend.dto.KpisDTO;
import com.tasf.backend.dto.SimulationStateDTO;
import com.tasf.backend.dto.ThroughputDiaDTO;
import com.tasf.backend.dto.VueloDTO;
import com.tasf.backend.service.DataLoaderService;
import com.tasf.backend.service.PlanningService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SimulationEngine {
    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int MAX_LOG_ENTRIES = 100;

    private final DataLoaderService dataLoaderService;
    private final PlanningService planningService;
    private final Random random = new Random();

    private ParametrosSimulacion params;
    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();
    private List<Envio> envios = new ArrayList<>();
    private List<Maleta> maletas = new ArrayList<>();
    private List<PlanDeViaje> planes = new ArrayList<>();
    private List<Cancelacion> cancelaciones = new ArrayList<>();
    private List<MetricaAlgoritmo> metricas = new ArrayList<>();
    private int diaActual;
    private LocalDateTime fechaSimulada;
    private boolean enEjecucion;
    private boolean finalizada;
    private List<String> logOperaciones = new ArrayList<>();

    private final Deque<String> logBuffer = new ArrayDeque<>();
    private final Map<String, String> maletaVueloActual = new HashMap<>();
    private final List<ThroughputDiaDTO> throughputHistorial = new ArrayList<>();

    public SimulationEngine(DataLoaderService dataLoaderService, PlanningService planningService) {
        this.dataLoaderService = dataLoaderService;
        this.planningService = planningService;
    }

    public synchronized void inicializar(ParametrosSimulacion params, List<Envio> todosLosEnvios) {
        reset();
        this.params = params;
        this.aeropuertos = deepCopyAeropuertos(dataLoaderService.getAeropuertos());
        this.vuelos = deepCopyVuelos(dataLoaderService.getVuelos());

        // Resolve the number of days requested by the user
        int filterDias = resolveDias(params);

        // Apply date-window filter: fecha >= fechaInicio AND fecha < fechaInicio + dias
        // When esColapso=true there is no upper bound
        LocalDate fechaInicio = params.getFechaInicio();
        boolean esColapso = Boolean.TRUE.equals(params.getEsColapso());

        List<Envio> filteredEnvios;
        if (esColapso) {
            filteredEnvios = todosLosEnvios.stream()
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().isBefore(fechaInicio))
                .collect(Collectors.toCollection(ArrayList::new));
        } else {
            LocalDate dateEnd = fechaInicio.plusDays(filterDias);
            filteredEnvios = todosLosEnvios.stream()
                .filter(e -> {
                    LocalDate d = e.getFechaHoraIngreso().toLocalDate();
                    return !d.isBefore(fechaInicio) && d.isBefore(dateEnd);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        }

        // Compute diasSimulacion: honour pre-set value (e.g. from tests), otherwise derive it
        int diasSimulacion;
        if (params.getDiasSimulacion() > 0) {
            diasSimulacion = params.getDiasSimulacion();
        } else if (esColapso) {
            diasSimulacion = computeDiasColapso(fechaInicio, filteredEnvios);
        } else {
            diasSimulacion = filterDias;
        }
        params.setDias(filterDias);
        params.setDiasSimulacion(diasSimulacion);

        this.envios = deepCopyEnvios(filteredEnvios);
        this.maletas = generarMaletas(this.envios);
        this.planes = new ArrayList<>();
        this.cancelaciones = new ArrayList<>();
        this.metricas = new ArrayList<>();
        this.fechaSimulada = params.getFechaInicio().atStartOfDay();

        this.envios.forEach(envio -> envio.setEstado(EstadoEnvio.PLANIFICADO));
        this.maletas.forEach(maleta -> {
            maleta.setEstado(EstadoMaleta.EN_ALMACEN);
        });

        PlanningResult planning = planningService.planificar(this.envios, this.vuelos, this.aeropuertos, this.params);
        this.planes = new ArrayList<>(planning.getPlanes());
        if (planning.getMetrica() != null) {
            this.metricas.add(planning.getMetrica());
        }

        Set<String> sinRuta = new HashSet<>(planning.getEnviosSinRuta());
        this.envios.stream()
            .filter(envio -> sinRuta.contains(envio.getIdEnvio()))
            .forEach(envio -> envio.setEstado(EstadoEnvio.RETRASADO));

        this.diaActual = 1;
        this.enEjecucion = true;
        this.finalizada = false;
        updateWarehouseOccupation();

        String algoritmoInicial = Optional.ofNullable(planning.getMetrica())
            .map(MetricaAlgoritmo::getAlgoritmoUsado)
            .orElse("N/A");
        addOperationLog("Simulation initialized - Day 1 - " + this.envios.size()
            + " envios - algorithm: " + algoritmoInicial
            + " - routes evaluated: " + Optional.ofNullable(planning.getMetrica()).map(MetricaAlgoritmo::getRutasEvaluadas).orElse(0));
    }

    public synchronized SimulationStateDTO avanzarDia() {
        if (!enEjecucion || params == null) {
            return getEstado();
        }

        addOperationLog("Processing day " + diaActual);

        // Vuelos have only LocalTime (daily repeating schedule). Cancellations from a
        // previous simulated day must be reset so that the same flight can operate again.
        vuelos.forEach(v -> v.setCancelado(false));

        // Snapshot warehouse state at the START of the day — before any departures or
        // deliveries — so that [OCUPACION] logs capture origin warehouses (e.g. OJAI)
        // while bags are still present. The end-of-day call below reflects the
        // post-delivery state and updates the domain field used by the API.
        updateWarehouseOccupation();
        accumulateOccupationSample();

        // Run up to 3 passes so that same-day connections work correctly.
        // On pass 1: leg-1 bags depart and arrive at the intermediate hub.
        // On pass 2: leg-2 bags depart from the hub (now EN_ALMACEN) and arrive at destination.
        // The existing state machine (EN_ALMACEN→EN_VUELO→EN_ALMACEN) prevents double-processing.
        for (int pass = 0; pass < 3; pass++) {
            processDepartures();
            processArrivals();
        }
        log.info("After arrivals: total maletas={}, EN_ALMACEN={}, EN_VUELO={}",
            maletas.size(),
            maletas.stream().filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN).count(),
            maletas.stream().filter(m -> m.getEstado() == EstadoMaleta.EN_VUELO).count());
        DeliveryStats deliveryStats = processDeliveries();
        checkSlaViolations();
        updateWarehouseOccupation();
        aeropuertos.forEach(ap ->
            log.info("Airport {} ocupacion={} maletas_en_almacen={}",
                ap.getCodigoIATA(),
                ap.getOcupacionActual(),
                maletas.stream()
                    .filter(m -> ap.getCodigoIATA().equals(m.getUbicacionActual()) &&
                        m.getEstado() == EstadoMaleta.EN_ALMACEN)
                    .count()));

        throughputHistorial.add(ThroughputDiaDTO.builder()
            .dia(diaActual)
            .maletasProcesadas(deliveryStats.delivered)
            .slaOk(deliveryStats.slaOk)
            .slaBreach(deliveryStats.slaBreach)
            .build());

        if (diaActual >= params.getDiasSimulacion()) {
            this.finalizada = true;
            this.enEjecucion = false;

            // Only mark RETRASADO for shipments whose SLA deadline falls within the
            // simulation window. A shipment whose deadline is after the last simulated
            // day is "in progress" — its deadline has not been reached, so penalising it
            // would distort the SLA metric.
            //
            // simulationEndDate = fechaInicio + (diasSimulacion - 1) because day 1 IS
            // fechaInicio, day 2 is +1, …, day n is +(n-1).
            LocalDate simulationEndDate = params.getFechaInicio().plusDays(params.getDiasSimulacion() - 1);
            envios.stream()
                .filter(e -> e.getEstado() != EstadoEnvio.ENTREGADO && e.getEstado() != EstadoEnvio.RETRASADO)
                .forEach(e -> {
                    LocalDate deadline = e.getFechaHoraIngreso().plusDays(e.getSla()).toLocalDate();
                    if (!deadline.isAfter(simulationEndDate)) {
                        // Deadline was within the simulation window and the bag was not
                        // delivered → genuine SLA violation.
                        e.setEstado(EstadoEnvio.RETRASADO);
                    }
                    // else: deadline is beyond simulationEndDate → bag is still "in
                    // progress"; leave its current state so buildKpis() can distinguish it.
                });

            // ── SLA AUDIT ────────────────────────────────────────────────────────────
            // Denominator = shipments whose SLA deadline falls on or before
            // simulationEndDate. A shipment whose deadline is after the last simulated
            // day could not physically be delivered within the window; counting it as a
            // failure would distort the SLA metric.
            //
            // deadline = fechaIngreso + sla (days)
            // included = !deadline.isAfter(simulationEndDate)
            //
            // For a 3-day run (Jan-2 → Jan-4):
            //   Envio Jan-2 SLA=1 → deadline Jan-3 ≤ Jan-4 → included
            //   Envio Jan-4 SLA=1 → deadline Jan-5 > Jan-4 → excluded
            log.info("SLA FILTER: total envios={} simulationEndDate={}", envios.size(), simulationEndDate);
            for (Envio e : envios) {
                LocalDate deadline = e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla());
                boolean included = !deadline.isAfter(simulationEndDate);
                log.info("SLA FILTER: envio {} included={} reason=!deadline({}).isAfter(simulationEndDate({}))",
                    e.getIdEnvio(), included, deadline, simulationEndDate);
            }
            long auditTotalWindow = envios.stream()
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().isAfter(simulationEndDate))
                .count();
            long auditEvaluable = envios.stream()
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simulationEndDate))
                .count();
            long auditEntregado = envios.stream()
                .filter(e -> e.getEstado() == EstadoEnvio.ENTREGADO)
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simulationEndDate))
                .count();
            long auditRetrasado = envios.stream()
                .filter(e -> e.getEstado() == EstadoEnvio.RETRASADO)
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simulationEndDate))
                .count();
            long auditInProgress = envios.stream()
                .filter(e -> e.getEstado() != EstadoEnvio.ENTREGADO && e.getEstado() != EstadoEnvio.RETRASADO)
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simulationEndDate))
                .count();
            double auditSla = auditEvaluable == 0 ? 0.0
                : Math.round(auditEntregado * 1000.0 / auditEvaluable) / 10.0;
            log.info("[SLA AUDIT] Total in window: {} | Deliverable: {} | ENTREGADO: {} | RETRASADO: {} | IN_PROGRESS: {} | SLA%: {}% | Denominador: {}",
                auditTotalWindow, auditEvaluable, auditEntregado, auditRetrasado, auditInProgress, auditSla, auditEvaluable);
            addOperationLog(String.format(
                "[SLA AUDIT] period=%d ENTREGADO=%d RETRASADO=%d IN_PROGRESS=%d SLA=%.1f%%",
                auditEvaluable, auditEntregado, auditRetrasado, auditInProgress, auditSla));
            // ─────────────────────────────────────────────────────────────────────────

            addOperationLog("Simulation completed - Day " + diaActual);
            return getEstado();
        }

        // Advance AFTER processing current day so day-1 departures are not skipped
        diaActual++;
        this.fechaSimulada = this.fechaSimulada.plusDays(1);

        return getEstado();
    }

    public synchronized void replanificar(List<Maleta> affectedMaletas) {
        replanificar(affectedMaletas, false);
    }

    private synchronized void replanificar(List<Maleta> affectedMaletas, boolean porIncidencia) {
        long start = System.currentTimeMillis();
        if (affectedMaletas == null || affectedMaletas.isEmpty()) {
            return;
        }

        Set<String> envioIds = affectedMaletas.stream().map(Maleta::getIdEnvio).collect(Collectors.toSet());
        List<Envio> afectados = envios.stream()
            .filter(envio -> envioIds.contains(envio.getIdEnvio()))
            .peek(envio -> envio.setEstado(EstadoEnvio.PLANIFICADO))
            .toList();

        if (afectados.isEmpty()) {
            return;
        }

        // For incidence replanning, route from the bags' current location rather than
        // the original origin airport — bags may already be partway through their journey.
        List<Envio> enviosParaPlanificar;
        if (porIncidencia) {
            Map<String, String> currentLocByEnvio = maletas.stream()
                .filter(m -> envioIds.contains(m.getIdEnvio()))
                .filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN || m.getEstado() == EstadoMaleta.RETRASADA)
                .collect(Collectors.toMap(Maleta::getIdEnvio, Maleta::getUbicacionActual, (a, b) -> a));

            enviosParaPlanificar = new ArrayList<>();
            for (Envio envio : afectados) {
                String currentLoc = currentLocByEnvio.get(envio.getIdEnvio());
                if (currentLoc == null) {
                    continue; // no active bags to replan for this envio
                }
                if (currentLoc.equals(envio.getAeropuertoDestino())) {
                    // Bags already at destination — mark delivered and skip replanning
                    envio.setEstado(EstadoEnvio.ENTREGADO);
                    maletas.stream()
                        .filter(m -> m.getIdEnvio().equals(envio.getIdEnvio()) && m.getEstado() == EstadoMaleta.EN_ALMACEN)
                        .forEach(m -> m.setEstado(EstadoMaleta.ENTREGADA));
                    continue;
                }
                enviosParaPlanificar.add(Envio.builder()
                    .idEnvio(envio.getIdEnvio())
                    .codigoAerolinea(envio.getCodigoAerolinea())
                    .aeropuertoOrigen(currentLoc)
                    .aeropuertoDestino(envio.getAeropuertoDestino())
                    .fechaHoraIngreso(envio.getFechaHoraIngreso())
                    .cantidadMaletas(envio.getCantidadMaletas())
                    .sla(envio.getSla())
                    .estado(EstadoEnvio.PLANIFICADO)
                    .build());
            }
            if (enviosParaPlanificar.isEmpty()) {
                return;
            }
        } else {
            enviosParaPlanificar = afectados;
        }

        PlanningResult result = porIncidencia
            ? planningService.planificarConIncidencia(enviosParaPlanificar, vuelos, aeropuertos, params)
            : planningService.planificar(enviosParaPlanificar, vuelos, aeropuertos, params);
        if (result.getMetrica() != null) {
            metricas.add(result.getMetrica());
        }

        Set<String> affectedIds = afectados.stream().map(Envio::getIdEnvio).collect(Collectors.toSet());
        planes = planes.stream().filter(plan -> !affectedIds.contains(plan.getIdEnvio())).collect(Collectors.toCollection(ArrayList::new));
        planes.addAll(result.getPlanes());

        Set<String> sinRuta = new HashSet<>(result.getEnviosSinRuta());
        for (Envio envio : afectados) {
            if (sinRuta.contains(envio.getIdEnvio())) {
                envio.setEstado(EstadoEnvio.RETRASADO);
                addOperationLog("ALERT replanification no route for envio " + envio.getIdEnvio());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        String algorithmUsed = Optional.ofNullable(result.getMetrica()).map(MetricaAlgoritmo::getAlgoritmoUsado).orElse("N/A");
        if (sinRuta.isEmpty()) {
            addOperationLog(String.format("[LOG] Replanificación exitosa (%s) en %d ms.", algorithmUsed, elapsed));
        } else {
            addOperationLog(String.format("[ALERTA] Replanificación parcial (%s). %d envíos se quedaron sin ruta viable.", 
                algorithmUsed, sinRuta.size()));
        }

        if (elapsed > 10_000) {
            addOperationLog("[ADVERTENCIA] La replanificación excedió los 10 segundos (RF 33): " + elapsed + " ms");
        }
    }

    public synchronized SimulationStateDTO getEstado() {
        if (params == null) {
            return SimulationStateDTO.builder()
                .diaActual(0)
                .totalDias(0)
                .fechaSimulada(null)
                .algoritmo(null)
                .metrica(null)
                .enEjecucion(false)
                .finalizada(false)
                .aeropuertos(List.of())
                .vuelos(List.of())
                .envios(List.of())
                .kpis(KpisDTO.builder()
                    .maletasEnTransito(0)
                    .maletasEntregadas(0)
                    .cumplimientoSLA(0.0)
                    .vuelosActivos(0)
                    .slaVencidos(0)
                    .ocupacionPromedioAlmacen(0.0)
                    .build())
                .throughputHistorial(List.of())
                .logOperaciones(List.of())
                .build();
        }

        // OPTIMIZATION: Indexing for state preparation
        Map<String, List<PlanDeViaje>> plansByFlight = new HashMap<>();
        Map<String, PlanDeViaje> latestPlanByEnvio = new HashMap<>();
        for (PlanDeViaje p : planes) {
            PlanDeViaje current = latestPlanByEnvio.get(p.getIdEnvio());
            if (current == null || p.getVersion() > current.getVersion()) {
                latestPlanByEnvio.put(p.getIdEnvio(), p);
            }
            for (var esc : p.getEscalas()) {
                plansByFlight.computeIfAbsent(esc.getCodigoVuelo(), k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, Envio> envioById = envios.stream().collect(Collectors.toMap(Envio::getIdEnvio, e -> e, (a, b) -> a));

        return SimulationStateDTO.builder()
            .diaActual(diaActual)
            .totalDias(params.getDiasSimulacion())
            .fechaSimulada(fechaSimulada.format(TS_FORMAT))
            .algoritmo(metricas.isEmpty() ? params.getAlgoritmo() : metricas.get(metricas.size() - 1).getAlgoritmoUsado())
            .metrica(metricas.isEmpty() ? null : metricas.get(metricas.size() - 1))
            .enEjecucion(enEjecucion)
            .finalizada(finalizada)
            .aeropuertos(aeropuertos.stream().map(this::toAeropuertoDto).toList())
            .vuelos(vuelos.stream().map(v -> toVueloDto(v, plansByFlight, envioById)).toList())
            .envios(envios.stream().map(e -> toEnvioDto(e, false, latestPlanByEnvio.get(e.getIdEnvio()))).toList())
            .kpis(buildKpis())
            .throughputHistorial(List.copyOf(throughputHistorial))
            .logOperaciones(List.copyOf(logOperaciones))
            .build();
    }

    public synchronized void reset() {
        this.params = null;
        this.aeropuertos = new ArrayList<>();
        this.vuelos = new ArrayList<>();
        this.envios = new ArrayList<>();
        this.maletas = new ArrayList<>();
        this.planes = new ArrayList<>();
        this.cancelaciones = new ArrayList<>();
        this.metricas = new ArrayList<>();
        this.diaActual = 0;
        this.fechaSimulada = null;
        this.enEjecucion = false;
        this.finalizada = false;
        this.logOperaciones = new ArrayList<>();
        this.logBuffer.clear();
        this.maletaVueloActual.clear();
        this.throughputHistorial.clear();
    }

    public synchronized boolean estaInicializada() {
        return params != null;
    }

    public synchronized ParametrosSimulacion getParams() {
        return params;
    }

    public synchronized List<AeropuertoDTO> getAeropuertosEstado() {
        if (params == null) {
            return dataLoaderService.getAeropuertos().stream().map(this::toAeropuertoDto).toList();
        }
        return aeropuertos.stream().map(this::toAeropuertoDto).toList();
    }

    public synchronized List<VueloDTO> getVuelosEstado() {
        Map<String, List<PlanDeViaje>> plansByFlight = new HashMap<>();
        for (PlanDeViaje p : planes) {
            for (var esc : p.getEscalas()) {
                plansByFlight.computeIfAbsent(esc.getCodigoVuelo(), k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, Envio> envioById = envios.stream().collect(Collectors.toMap(Envio::getIdEnvio, e -> e, (a, b) -> a));
        return vuelos.stream().map(v -> toVueloDto(v, plansByFlight, envioById)).toList();
    }

    public synchronized List<EnvioDTO> getEnviosEstado() {
        Map<String, PlanDeViaje> latestPlanByEnvio = new HashMap<>();
        for (PlanDeViaje p : planes) {
            PlanDeViaje current = latestPlanByEnvio.get(p.getIdEnvio());
            if (current == null || p.getVersion() > current.getVersion()) {
                latestPlanByEnvio.put(p.getIdEnvio(), p);
            }
        }
        return envios.stream().map(envio -> toEnvioDto(envio, false, latestPlanByEnvio.get(envio.getIdEnvio()))).toList();
    }

    public synchronized Optional<EnvioDTO> getEnvioPorId(String idEnvio) {
        return envios.stream()
            .filter(envio -> envio.getIdEnvio().equals(idEnvio))
            .findFirst()
            .map(envio -> {
                PlanDeViaje plan = planes.stream()
                    .filter(p -> p.getIdEnvio().equals(envio.getIdEnvio()))
                    .max(Comparator.comparingInt(PlanDeViaje::getVersion))
                    .orElse(null);
                return toEnvioDto(envio, true, plan);
            });
    }

    private void processDepartures() {
        LocalDate today = fechaSimulada.toLocalDate();
        Map<String, Envio> envioById = envios.stream().collect(Collectors.toMap(Envio::getIdEnvio, e -> e, (a, b) -> a));
        Map<String, Vuelo> vueloByCode = vuelos.stream().collect(Collectors.toMap(Vuelo::getCodigoVuelo, v -> v, (a, b) -> a));
        Map<String, Aeropuerto> airportByCode = aeropuertos.stream().collect(Collectors.toMap(Aeropuerto::getCodigoIATA, a -> a, (a, b) -> a));

        // OPTIMIZATION: Index maletas by shipment ID
        Map<String, List<Maleta>> maletasByEnvio = maletas.stream()
            .collect(Collectors.groupingBy(Maleta::getIdEnvio));

        for (PlanDeViaje plan : planes) {
            Envio envio = envioById.get(plan.getIdEnvio());
            if (envio == null || envio.getEstado() == EstadoEnvio.ENTREGADO || envio.getEstado() == EstadoEnvio.CANCELADO) {
                continue;
            }
            for (var escala : plan.getEscalas()) {
                if (!escala.getHoraSalidaEst().toLocalDate().equals(today)) {
                    continue;
                }
                Vuelo vuelo = vueloByCode.get(escala.getCodigoVuelo());
                if (vuelo == null || vuelo.isCancelado()) {
                    continue;
                }

                String legOrigin = vuelo.getOrigen();
                List<Maleta> maletasEnvio = maletasByEnvio.getOrDefault(envio.getIdEnvio(), List.of()).stream()
                    .filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN || m.getEstado() == EstadoMaleta.RETRASADA)
                    .filter(m -> legOrigin.equals(m.getUbicacionActual()))
                    .toList();
                
                for (Maleta maleta : maletasEnvio) {
                    maleta.setEstado(EstadoMaleta.EN_VUELO);
                    maletaVueloActual.put(maleta.getIdMaleta(), vuelo.getCodigoVuelo());
                }
                if (!maletasEnvio.isEmpty()) {
                    vuelo.setCargaActual(vuelo.getCargaActual() + maletasEnvio.size());
                    if (envio.getEstado() != EstadoEnvio.RETRASADO) {
                        envio.setEstado(EstadoEnvio.EN_TRANSITO);
                    }
                    Aeropuerto originAirport = airportByCode.get(legOrigin);
                    if (originAirport != null) {
                        originAirport.setMaletasEnviadas(originAirport.getMaletasEnviadas() + maletasEnvio.size());
                    }
                }
            }
        }
    }

    private void processArrivals() {
        LocalDate today = fechaSimulada.toLocalDate();
        Map<String, Vuelo> vueloByCode = vuelos.stream().collect(Collectors.toMap(Vuelo::getCodigoVuelo, v -> v, (a, b) -> a));
        Map<String, Aeropuerto> airportByCode = aeropuertos.stream().collect(Collectors.toMap(Aeropuerto::getCodigoIATA, a -> a, (a, b) -> a));

        for (PlanDeViaje plan : planes) {
            for (var escala : plan.getEscalas()) {
                if (!escala.getHoraLlegadaEst().toLocalDate().equals(today)) {
                    continue;
                }
                Vuelo vuelo = vueloByCode.get(escala.getCodigoVuelo());
                if (vuelo == null || vuelo.isCancelado()) {
                    continue;
                }

                List<Maleta> inFlight = maletas.stream()
                    .filter(m -> m.getIdEnvio().equals(plan.getIdEnvio()))
                    .filter(m -> m.getEstado() == EstadoMaleta.EN_VUELO)
                    .filter(m -> escala.getCodigoVuelo().equals(maletaVueloActual.get(m.getIdMaleta())))
                    .toList();

                for (Maleta maleta : inFlight) {
                    maleta.setUbicacionActual(escala.getCodigoAeropuerto());
                    maleta.setEstado(EstadoMaleta.EN_ALMACEN);
                    maletaVueloActual.remove(maleta.getIdMaleta());
                }
                if (!inFlight.isEmpty()) {
                    Aeropuerto arrivalAirport = airportByCode.get(escala.getCodigoAeropuerto());
                    if (arrivalAirport != null) {
                        arrivalAirport.setMaletasRecibidas(arrivalAirport.getMaletasRecibidas() + inFlight.size());
                    }
                }

                vuelo.setCargaActual(Math.max(0, vuelo.getCargaActual() - inFlight.size()));
                // Warehouse occupation is fully recalculated by updateWarehouseOccupation() after
                // all arrivals/deliveries are processed, so we don't increment it here to avoid
                // double-counting.
            }
        }
    }

    private DeliveryStats processDeliveries() {
        int delivered = 0;
        int slaOk = 0;
        int slaBreach = 0;
        Map<String, Envio> envioById = envios.stream().collect(Collectors.toMap(Envio::getIdEnvio, e -> e, (a, b) -> a));
        Map<String, Aeropuerto> airportByCode = aeropuertos.stream().collect(Collectors.toMap(Aeropuerto::getCodigoIATA, a -> a, (a, b) -> a));

        int entregadosEstePaso = 0;
        for (Maleta maleta : maletas) {
            Envio envio = envioById.get(maleta.getIdEnvio());
            if (envio == null || maleta.getEstado() != EstadoMaleta.EN_ALMACEN) {
                continue;
            }
            log.info("DELIVERY CHECK: envio {} estado={} ubicacion={} destino={} match={}",
                envio.getIdEnvio(), maleta.getEstado(), maleta.getUbicacionActual(),
                envio.getAeropuertoDestino(),
                envio.getAeropuertoDestino().equals(maleta.getUbicacionActual()));

            if (envio.getAeropuertoDestino().equals(maleta.getUbicacionActual())) {
                maleta.setEstado(EstadoMaleta.ENTREGADA);
                delivered++;
                // Mark the envio ENTREGADO immediately on first bag arrival; do not wait
                // for allMatch across all bags (which breaks when envio IDs collide across
                // airport files, leaving some bags of the same ID at a different airport).
                if (envio.getEstado() != EstadoEnvio.ENTREGADO) {
                    envio.setEstado(EstadoEnvio.ENTREGADO);
                    entregadosEstePaso++;
                    log.info("Envio {} entregado en {}", envio.getIdEnvio(), envio.getAeropuertoDestino());
                }
                Aeropuerto destino = airportByCode.get(maleta.getUbicacionActual());
                if (destino != null) {
                    destino.setOcupacionActual(Math.max(0, destino.getOcupacionActual() - 1));
                }
                if (fechaSimulada.isAfter(envio.getFechaHoraIngreso().plusDays(envio.getSla()))) {
                    slaBreach++;
                } else {
                    slaOk++;
                }
            }
        }

        // Second pass: catch any envio whose bags all reached ENTREGADA without going through
        // the first loop (e.g. via replanning that set bags directly). entregadosEstePaso was
        // already incremented above for the common path, so only add here for the rare case.
        for (Envio envio : envios) {
            if (envio.getEstado() == EstadoEnvio.ENTREGADO) continue;
            List<Maleta> maletasEnvio = maletas.stream()
                .filter(m -> m.getIdEnvio().equals(envio.getIdEnvio()))
                .toList();
            boolean allDelivered = !maletasEnvio.isEmpty()
                && maletasEnvio.stream().allMatch(m -> m.getEstado() == EstadoMaleta.ENTREGADA);
            if (allDelivered) {
                envio.setEstado(EstadoEnvio.ENTREGADO);
                entregadosEstePaso++;
                log.info("Envio {} entregado en {}", envio.getIdEnvio(), envio.getAeropuertoDestino());
            }
        }
        log.info("processDeliveries: {} envios entregados this pass", entregadosEstePaso);

        return new DeliveryStats(delivered, slaOk, slaBreach);
    }

    private void checkSlaViolations() {
        for (Envio envio : envios) {
            if (envio.getEstado() == EstadoEnvio.ENTREGADO) {
                continue;
            }
            LocalDateTime deadline = envio.getFechaHoraIngreso().plusDays(envio.getSla());
            if (fechaSimulada.isAfter(deadline)) {
                envio.setEstado(EstadoEnvio.RETRASADO);
                maletas.stream()
                    .filter(m -> m.getIdEnvio().equals(envio.getIdEnvio()))
                    .filter(m -> m.getEstado() != EstadoMaleta.ENTREGADA)
                    .forEach(m -> m.setEstado(EstadoMaleta.RETRASADA));

                long exceeded = Duration.between(deadline, fechaSimulada).toHours();
                String message = "SLA exceeded for envio " + envio.getIdEnvio() + " by " + exceeded + " sim-hours";
                log.warn(message);
                addOperationLog("WARNING " + message);
            }
        }
    }

    private void cancelRandomFlightsAndReplan() {
        LocalDate today = fechaSimulada == null ? null : fechaSimulada.toLocalDate();
        if (today == null) return;

        List<Vuelo> cancelledToday = detectCancellations(today);
        for (Vuelo vuelo : cancelledToday) {
            List<Maleta> affected = rescueBags(vuelo, today);
            if (!affected.isEmpty()) {
                addOperationLog(String.format("[INCIDENCIA] Vuelo %s cancelado. Rescatadas %d maletas. Iniciando replanificación...", 
                    vuelo.getCodigoVuelo(), affected.size()));
                replanificar(affected, true);
            } else {
                addOperationLog("[INCIDENCIA] Vuelo " + vuelo.getCodigoVuelo() + " cancelado. Sin maletas afectadas hoy.");
            }
        }
    }

    private List<Vuelo> detectCancellations(LocalDate today) {
        double probability = 0.05d + (random.nextDouble() * 0.03d);
        Set<String> plannedToday = planes.stream()
            .flatMap(plan -> plan.getEscalas().stream())
            .filter(e -> e.getHoraSalidaEst() != null && e.getHoraSalidaEst().toLocalDate().equals(today))
            .map(Escala::getCodigoVuelo)
            .collect(Collectors.toSet());

        List<Vuelo> cancelled = new ArrayList<>();
        for (Vuelo vuelo : vuelos) {
            if (!plannedToday.contains(vuelo.getCodigoVuelo()) || vuelo.isCancelado()) continue;
            if (random.nextDouble() < probability) {
                vuelo.setCancelado(true);
                cancelaciones.add(Cancelacion.builder()
                    .id("CAN-" + vuelo.getCodigoVuelo() + "-" + System.nanoTime())
                    .codigoVuelo(vuelo.getCodigoVuelo())
                    .fecha(today)
                    .hora(LocalTime.now())
                    .motivo("Random disruption event")
                    .build());
                cancelled.add(vuelo);
            }
        }
        return cancelled;
    }

    public synchronized void cancelarVueloManualmente(String codigoVuelo) {
        if (!enEjecucion) return;

        Vuelo vuelo = vuelos.stream()
            .filter(v -> v.getCodigoVuelo().equals(codigoVuelo))
            .findFirst()
            .orElse(null);

        if (vuelo == null || vuelo.isCancelado()) return;

        LocalDate today = fechaSimulada.toLocalDate();
        vuelo.setCancelado(true);
        cancelaciones.add(Cancelacion.builder()
            .id("CAN-MANUAL-" + vuelo.getCodigoVuelo() + "-" + System.nanoTime())
            .codigoVuelo(vuelo.getCodigoVuelo())
            .fecha(today)
            .hora(LocalTime.now())
            .motivo("Manual cancellation by operator")
            .build());

        List<Maleta> affected = rescueBags(vuelo, today);
        if (!affected.isEmpty()) {
            addOperationLog(String.format("[INCIDENCIA] Vuelo %s cancelado MANUALMENTE. Rescatadas %d maletas. Iniciando replanificación...", 
                vuelo.getCodigoVuelo(), affected.size()));
            replanificar(affected, true);
        } else {
            addOperationLog("[INCIDENCIA] Vuelo " + vuelo.getCodigoVuelo() + " cancelado MANUALMENTE. Sin maletas afectadas hoy.");
        }
    }

    public synchronized void cancelarEnvioManualmente(String idEnvio) {
        if (!enEjecucion) return;

        Envio envio = envios.stream()
            .filter(e -> e.getIdEnvio().equals(idEnvio))
            .findFirst()
            .orElse(null);

        if (envio == null || envio.getEstado() == EstadoEnvio.CANCELADO) return;

        // Restriction: Cannot cancel if already in transit
        boolean alreadyInTransit = maletas.stream()
            .filter(m -> m.getIdEnvio().equals(idEnvio))
            .anyMatch(m -> m.getEstado() == EstadoMaleta.EN_VUELO);
        
        if (alreadyInTransit) {
            addOperationLog("[ADVERTENCIA] No se puede cancelar el envío " + idEnvio + " porque ya está en vuelo.");
            return;
        }

        envio.setEstado(EstadoEnvio.CANCELADO);
        addOperationLog("[INCIDENCIA] Envío " + idEnvio + " cancelado. Liberando capacidad y replanificando...");

        for (Maleta maleta : maletas) {
            if (maleta.getIdEnvio().equals(idEnvio)) {
                maleta.setEstado(EstadoMaleta.CANCELADA);
                maletaVueloActual.remove(maleta.getIdMaleta());
            }
        }

        // Trigger re-planning for all other pending/delayed bags to use the freed capacity
        List<Maleta> toOptimize = maletas.stream()
            .filter(m -> (m.getEstado() == EstadoMaleta.EN_ALMACEN || m.getEstado() == EstadoMaleta.RETRASADA) 
                && !m.getIdEnvio().equals(idEnvio))
            .toList();

        if (!toOptimize.isEmpty()) {
            replanificar(toOptimize, true);
        }
    }

    private List<Maleta> rescueBags(Vuelo vuelo, LocalDate today) {
        Set<String> affectedEnvioIds = planes.stream()
            .filter(plan -> plan.getEscalas().stream()
                .anyMatch(e -> vuelo.getCodigoVuelo().equals(e.getCodigoVuelo())
                    && e.getHoraSalidaEst() != null
                    && e.getHoraSalidaEst().toLocalDate().equals(today)))
            .map(PlanDeViaje::getIdEnvio)
            .collect(Collectors.toSet());

        if (affectedEnvioIds.isEmpty()) return List.of();

        List<Maleta> affected = new ArrayList<>();
        int unloadedCount = 0;
        for (Maleta maleta : maletas) {
            if (!affectedEnvioIds.contains(maleta.getIdEnvio()) || maleta.getEstado() == EstadoMaleta.ENTREGADA) continue;

            // If it was supposed to be in this flight, put it back in the warehouse at the origin
            boolean wasInCanceledFlight = maleta.getEstado() == EstadoMaleta.EN_VUELO
                && vuelo.getCodigoVuelo().equals(maletaVueloActual.get(maleta.getIdMaleta()));
            
            if (wasInCanceledFlight) {
                maleta.setEstado(EstadoMaleta.EN_ALMACEN);
                maleta.setUbicacionActual(vuelo.getOrigen());
                maletaVueloActual.remove(maleta.getIdMaleta());
                unloadedCount++;
            }

            if (maleta.getEstado() == EstadoMaleta.EN_ALMACEN || maleta.getEstado() == EstadoMaleta.RETRASADA) {
                affected.add(maleta);
            }
        }

        if (unloadedCount > 0) {
            vuelo.setCargaActual(Math.max(0, vuelo.getCargaActual() - unloadedCount));
        }
        return affected;
    }

    private void updateWarehouseOccupation() {
        Map<String, Long> counts = maletas.stream()
            .filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN)
            .collect(Collectors.groupingBy(Maleta::getUbicacionActual, Collectors.counting()));

        for (Aeropuerto aeropuerto : aeropuertos) {
            long count = counts.getOrDefault(aeropuerto.getCodigoIATA(), 0L);
            aeropuerto.setOcupacionActual((int) count);
            if (count > 0) {
                int cap = aeropuerto.getCapacidadAlmacen();
                double pct = cap > 0 ? (count * 100.0 / cap) : 0.0;
                log.info("[OCUPACION] Airport: {} | Bags in warehouse: {} | Capacity: {} | Occupation: {}%",
                    aeropuerto.getCodigoIATA(), count, cap, String.format("%.1f", pct));
            }
        }
    }

    private void accumulateOccupationSample() {
        for (Aeropuerto aeropuerto : aeropuertos) {
            int count = aeropuerto.getOcupacionActual();
            int cap = aeropuerto.getCapacidadAlmacen();
            double pct = cap > 0 ? (count * 100.0 / cap) : 0.0;
            aeropuerto.setOcupacionPorcentajeSuma(aeropuerto.getOcupacionPorcentajeSuma() + pct);
            aeropuerto.setOcupacionMuestras(aeropuerto.getOcupacionMuestras() + 1);
            if (count > aeropuerto.getOcupacionMaximaBolsas()) {
                aeropuerto.setOcupacionMaximaBolsas(count);
            }
        }
    }

    private KpisDTO buildKpis() {
        // EN_VUELO is a transient state (exists only during processDepartures/Arrivals).
        // Count maletas of envíos that are still active (PLANIFICADO or EN_TRANSITO).
        Set<String> enviosActivos = envios.stream()
            .filter(e -> e.getEstado() == EstadoEnvio.PLANIFICADO || e.getEstado() == EstadoEnvio.EN_TRANSITO)
            .map(Envio::getIdEnvio)
            .collect(Collectors.toSet());
        int maletasEnTransito = (int) maletas.stream()
            .filter(m -> enviosActivos.contains(m.getIdEnvio()))
            .count();
        int maletasEntregadas = (int) maletas.stream().filter(m -> m.getEstado() == EstadoMaleta.ENTREGADA).count();
        LocalDate today = fechaSimulada == null ? null : fechaSimulada.toLocalDate();
        Set<String> vuelosEnUso = planes.stream()
            .flatMap(p -> p.getEscalas().stream())
            .filter(e -> e.getHoraSalidaEst() != null && today != null &&
                e.getHoraSalidaEst().toLocalDate().equals(today))
            .map(Escala::getCodigoVuelo)
            .collect(Collectors.toSet());
        int vuelosActivos = vuelosEnUso.size();
        int slaVencidos = (int) envios.stream().filter(e -> e.getEstado() == EstadoEnvio.RETRASADO).count();

        // SLA compliance denominator: shipments whose deadline (fechaIngreso + sla)
        // falls on or before simulationEndDate. Only these shipments could have been
        // delivered within the simulation window; including others would unfairly
        // inflate the failure count.
        LocalDate simEnd = params == null ? null
            : params.getFechaInicio().plusDays(params.getDiasSimulacion() - 1);
        long totalEnvios = simEnd == null ? envios.size()
            : envios.stream()
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simEnd))
                .count();
        long entregadosEnSla = simEnd == null
            ? envios.stream().filter(e -> e.getEstado() == EstadoEnvio.ENTREGADO).count()
            : envios.stream()
                .filter(e -> e.getEstado() == EstadoEnvio.ENTREGADO)
                .filter(e -> !e.getFechaHoraIngreso().toLocalDate().plusDays(e.getSla()).isAfter(simEnd))
                .count();
        double cumplimientoSla = totalEnvios == 0 ? 0.0 : Math.round(entregadosEnSla * 1000.0 / totalEnvios) / 10.0;
        // When the simulation is finalised all bags are ENTREGADA so the live stream
        // would return 0. Use the historical average accumulated by accumulateOccupationSample()
        // instead. During the simulation (finalizada=false) keep the live computation so
        // that the map still shows real-time occupation correctly.
        double ocupacionPromedio;
        if (finalizada) {
            ocupacionPromedio = aeropuertos.stream()
                .mapToDouble(a -> a.getOcupacionMuestras() == 0 ? 0.0
                    : a.getOcupacionPorcentajeSuma() / a.getOcupacionMuestras())
                .average()
                .orElse(0.0d);
        } else {
            Map<String, Long> liveOcupacion = maletas.stream()
                .filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN)
                .collect(Collectors.groupingBy(Maleta::getUbicacionActual, Collectors.counting()));
            ocupacionPromedio = aeropuertos.stream()
                .mapToDouble(a -> {
                    if (a.getCapacidadAlmacen() == 0) return 0.0;
                    long bagCount = liveOcupacion.getOrDefault(a.getCodigoIATA(), 0L);
                    return bagCount * 100.0d / a.getCapacidadAlmacen();
                })
                .average()
                .orElse(0.0d);
        }

        return KpisDTO.builder()
            .maletasEnTransito(maletasEnTransito)
            .maletasEntregadas(maletasEntregadas)
            .cumplimientoSLA(cumplimientoSla)
            .vuelosActivos(vuelosActivos)
            .slaVencidos(slaVencidos)
            .ocupacionPromedioAlmacen(ocupacionPromedio)
            .build();
    }

    private AeropuertoDTO toAeropuertoDto(Aeropuerto airport) {
        int capacidad = airport.getCapacidadAlmacen();
        int ocupacion = (int) maletas.stream()
            .filter(m -> m.getUbicacionActual().equals(airport.getCodigoIATA()) &&
                m.getEstado() == EstadoMaleta.EN_ALMACEN)
            .count();

        String semaforo;
        if (capacidad == 0) {
            semaforo = "verde";
        } else {
            double pct = (ocupacion * 100.0) / capacidad;
            double ambarThreshold = params == null ? 85.0 : params.getUmbralSemaforoAmbar();
            double verdeThreshold = params == null ? 60.0 : params.getUmbralSemaforoVerde();
            if (pct >= ambarThreshold) {
                semaforo = "rojo";
            } else if (pct >= verdeThreshold) {
                semaforo = "ambar";
            } else {
                semaforo = "verde";
            }
        }

        double ocupProm = airport.getOcupacionMuestras() == 0 ? 0.0
            : airport.getOcupacionPorcentajeSuma() / airport.getOcupacionMuestras();
        double ocupMax = capacidad > 0
            ? (airport.getOcupacionMaximaBolsas() * 100.0 / capacidad) : 0.0;

        return AeropuertoDTO.builder()
            .codigoIATA(airport.getCodigoIATA())
            .nombre(airport.getNombre())
            .ciudad(airport.getCiudad())
            .continente(airport.getContinente())
            .lat(airport.getLat())
            .lng(airport.getLng())
            .capacidadAlmacen(capacidad)
            .ocupacionActual(ocupacion)
            .semaforo(semaforo)
            .maletasRecibidas(airport.getMaletasRecibidas())
            .maletasEnviadas(airport.getMaletasEnviadas())
            .ocupacionPromedio(ocupProm)
            .ocupacionMaxima(ocupMax)
            .build();
    }

    private VueloDTO toVueloDto(Vuelo vuelo, Map<String, List<PlanDeViaje>> plansByFlight, Map<String, Envio> envioById) {
        List<PlanDeViaje> relatedPlans = plansByFlight.getOrDefault(vuelo.getCodigoVuelo(), List.of());
        boolean usedByAnyPlan = !relatedPlans.isEmpty();
        
        int maletasAsignadas = relatedPlans.stream()
            .map(p -> envioById.get(p.getIdEnvio()))
            .filter(e -> e != null && e.getEstado() != EstadoEnvio.ENTREGADO && e.getEstado() != EstadoEnvio.CANCELADO)
            .mapToInt(Envio::getCantidadMaletas)
            .sum();

        return VueloDTO.builder()
            .codigoVuelo(vuelo.getCodigoVuelo())
            .origen(vuelo.getOrigen())
            .destino(vuelo.getDestino())
            .tipo(vuelo.getTipo())
            .estado(resolveVueloEstado(vuelo, usedByAnyPlan))
            .cargaActual(vuelo.getCargaActual())
            .maletasAsignadas(maletasAsignadas)
            .capacidadTotal(vuelo.getCapacidadTotal())
            .fraction(resolveFraction(vuelo, relatedPlans))
            .horaSalida(vuelo.getHoraSalida().toString())
            .horaLlegada(vuelo.getHoraLlegada().toString())
            .enUso(usedByAnyPlan)
            .build();
    }

    private EnvioDTO toEnvioDto(Envio envio, boolean includePlanDetail, PlanDeViaje plan) {
        if (plan == null) {
             plan = planes.stream()
                .filter(p -> p.getIdEnvio().equals(envio.getIdEnvio()))
                .max(Comparator.comparingInt(PlanDeViaje::getVersion))
                .orElse(null);
        }
        LocalDateTime deadline = envio.getFechaHoraIngreso().plusDays(envio.getSla());

        return EnvioDTO.builder()
            .idEnvio(envio.getIdEnvio())
            .codigoAerolinea(envio.getCodigoAerolinea())
            .aeropuertoOrigen(envio.getAeropuertoOrigen())
            .aeropuertoDestino(envio.getAeropuertoDestino())
            .cantidadMaletas(envio.getCantidadMaletas())
            .estado(envio.getEstado().name())
            .sla(envio.getSla())
            .fechaHoraIngreso(envio.getFechaHoraIngreso().format(TS_FORMAT))
            .planResumen(buildPlanResumen(envio, plan))
            .tiempoRestante(formatRemainingTime(deadline))
            .planDetalle(includePlanDetail ? plan : null)
            .build();
    }

    private String buildPlanResumen(Envio envio, PlanDeViaje plan) {
        if (plan == null || plan.getEscalas() == null || plan.getEscalas().isEmpty()) {
            return envio.getAeropuertoOrigen() + " -> " + envio.getAeropuertoDestino() + " (no route)";
        }
        List<String> hubs = plan.getEscalas().stream()
            .map(escala -> escala.getCodigoAeropuerto())
            .filter(code -> !code.equals(envio.getAeropuertoDestino()))
            .distinct()
            .toList();

        if (hubs.isEmpty()) {
            return envio.getAeropuertoOrigen() + " -> " + envio.getAeropuertoDestino();
        }
        return envio.getAeropuertoOrigen() + " -> " + envio.getAeropuertoDestino() + " via " + String.join(", ", hubs);
    }

    private String formatRemainingTime(LocalDateTime deadline) {
        if (fechaSimulada == null) {
            return "N/A";
        }
        Duration remaining = Duration.between(fechaSimulada, deadline);
        if (remaining.isNegative()) {
            return "vencido " + Math.abs(remaining.toHours()) + "h";
        }
        long days = remaining.toDays();
        long hours = remaining.minusDays(days).toHours();
        return days + "d " + hours + "h";
    }

    private String resolveVueloEstado(Vuelo vuelo, boolean inUse) {
        if (vuelo.isCancelado()) {
            return "cancelado";
        }
        if (inUse && vuelo.getCargaActual() == 0 && diaActual > 1) {
            return "completado";
        }
        return "activo";
    }

    private double resolveFraction(Vuelo vuelo, List<PlanDeViaje> relatedPlans) {
        if (fechaSimulada == null || relatedPlans.isEmpty()) {
            return 0.0d;
        }
        // Simplified midpoint fraction for animation performance
        return 0.5d;
    }

    private void addOperationLog(String message) {
        String value = LocalDateTime.now().format(TS_FORMAT) + " | " + message;
        logBuffer.addLast(value);
        while (logBuffer.size() > MAX_LOG_ENTRIES) {
            logBuffer.removeFirst();
        }
        this.logOperaciones = new ArrayList<>(logBuffer);
        log.info(message);
    }

    private List<Aeropuerto> deepCopyAeropuertos(List<Aeropuerto> source) {
        return source.stream().map(a -> Aeropuerto.builder()
            .codigoIATA(a.getCodigoIATA())
            .nombre(a.getNombre())
            .ciudad(a.getCiudad())
            .pais(a.getPais())
            .continente(a.getContinente())
            .huso(a.getHuso())
            .capacidadAlmacen(a.getCapacidadAlmacen())
            .lat(a.getLat())
            .lng(a.getLng())
            .ocupacionActual(a.getOcupacionActual())
            .build()).collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Vuelo> deepCopyVuelos(List<Vuelo> source) {
        return source.stream().map(v -> Vuelo.builder()
            .codigoVuelo(v.getCodigoVuelo())
            .origen(v.getOrigen())
            .destino(v.getDestino())
            .horaSalida(v.getHoraSalida())
            .horaLlegada(v.getHoraLlegada())
            .capacidadTotal(v.getCapacidadTotal())
            .tipo(v.getTipo())
            .cargaActual(v.getCargaActual())
            .cancelado(v.isCancelado())
            .build()).collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Envio> deepCopyEnvios(List<Envio> source) {
        return source.stream().map(e -> Envio.builder()
            .idEnvio(e.getIdEnvio())
            .codigoAerolinea(e.getCodigoAerolinea())
            .aeropuertoOrigen(e.getAeropuertoOrigen())
            .aeropuertoDestino(e.getAeropuertoDestino())
            .fechaHoraIngreso(e.getFechaHoraIngreso())
            .cantidadMaletas(e.getCantidadMaletas())
            .sla(e.getSla())
            .estado(e.getEstado())
            .build()).collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Maleta> generarMaletas(List<Envio> enviosInput) {
        List<Maleta> generated = new ArrayList<>();
        for (Envio envio : enviosInput) {
            for (int i = 1; i <= envio.getCantidadMaletas(); i++) {
                generated.add(Maleta.builder()
                    .idMaleta(envio.getIdEnvio() + "-" + i)
                    .idEnvio(envio.getIdEnvio())
                    .ubicacionActual(envio.getAeropuertoOrigen())
                    .estado(EstadoMaleta.EN_ALMACEN)
                    .build());
            }
        }
        return generated;
    }

    private int resolveDias(ParametrosSimulacion p) {
        if (p.getDias() != null && p.getDias() > 0) {
            return p.getDias();
        }
        if (p.getDiasSimulacion() > 0) {
            return p.getDiasSimulacion();
        }
        throw new IllegalArgumentException("dias must be greater than zero");
    }

    private int computeDiasColapso(LocalDate fechaInicio, List<Envio> envios) {
        LocalDate lastDate = envios.stream()
            .map(Envio::getFechaHoraIngreso)
            .map(LocalDateTime::toLocalDate)
            .max(LocalDate::compareTo)
            .orElse(fechaInicio);
        return (int) Math.max(1, lastDate.toEpochDay() - fechaInicio.toEpochDay() + 1);
    }

    private static class DeliveryStats {
        private final int delivered;
        private final int slaOk;
        private final int slaBreach;

        private DeliveryStats(int delivered, int slaOk, int slaBreach) {
            this.delivered = delivered;
            this.slaOk = slaOk;
            this.slaBreach = slaBreach;
        }
    }
}
