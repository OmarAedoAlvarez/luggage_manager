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

    public synchronized void inicializar(ParametrosSimulacion params, List<Envio> enviosInput) {
        reset();
        this.params = params;
        this.aeropuertos = deepCopyAeropuertos(dataLoaderService.getAeropuertos());
        this.vuelos = deepCopyVuelos(dataLoaderService.getVuelos());
        this.envios = deepCopyEnvios(enviosInput);
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

        addOperationLog("Simulation initialized - Day 1 - " + this.envios.size()
            + " envios - algorithm: " + this.params.getAlgoritmo()
            + " - routes evaluated: " + Optional.ofNullable(planning.getMetrica()).map(MetricaAlgoritmo::getRutasEvaluadas).orElse(0));
    }

    public synchronized SimulationStateDTO avanzarDia() {
        if (!enEjecucion || params == null) {
            return getEstado();
        }

        addOperationLog("Processing day " + diaActual);

        processDepartures();
        processArrivals();
        log.info("After arrivals: total maletas={}, EN_ALMACEN={}, EN_VUELO={}",
            maletas.size(),
            maletas.stream().filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN).count(),
            maletas.stream().filter(m -> m.getEstado() == EstadoMaleta.EN_VUELO).count());
        DeliveryStats deliveryStats = processDeliveries();
        checkSlaViolations();
        cancelRandomFlightsAndReplan();
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
            // Mark any envío still not delivered as RETRASADO
            envios.stream()
                .filter(e -> e.getEstado() != EstadoEnvio.ENTREGADO && e.getEstado() != EstadoEnvio.RETRASADO)
                .forEach(e -> e.setEstado(EstadoEnvio.RETRASADO));
            addOperationLog("Simulation completed - Day " + diaActual);
            return getEstado();
        }

        // Advance AFTER processing current day so day-1 departures are not skipped
        diaActual++;
        this.fechaSimulada = this.fechaSimulada.plusDays(1);

        return getEstado();
    }

    public synchronized void replanificar(List<Maleta> affectedMaletas) {
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

        PlanningResult result = planningService.planificar(afectados, vuelos, aeropuertos, params);
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
        addOperationLog("Replanification executed for " + afectados.size() + " envios in " + elapsed + " ms");
        if (elapsed > 10_000) {
            addOperationLog("WARNING replanification exceeded 10 seconds (RF 33): " + elapsed + " ms");
        }
    }

    public synchronized SimulationStateDTO getEstado() {
        if (params == null) {
            return SimulationStateDTO.builder()
                .diaActual(0)
                .totalDias(0)
                .fechaSimulada(null)
                .algoritmo(null)
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

        return SimulationStateDTO.builder()
            .diaActual(diaActual)
            .totalDias(params.getDiasSimulacion())
            .fechaSimulada(fechaSimulada.format(TS_FORMAT))
            .algoritmo(params.getAlgoritmo())
            .enEjecucion(enEjecucion)
            .finalizada(finalizada)
            .aeropuertos(aeropuertos.stream().map(this::toAeropuertoDto).toList())
            .vuelos(vuelos.stream().map(this::toVueloDto).toList())
            .envios(envios.stream().map(envio -> toEnvioDto(envio, false)).toList())
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

    public synchronized List<AeropuertoDTO> getAeropuertosEstado() {
        if (params == null) {
            return dataLoaderService.getAeropuertos().stream().map(this::toAeropuertoDto).toList();
        }
        return aeropuertos.stream().map(this::toAeropuertoDto).toList();
    }

    public synchronized List<VueloDTO> getVuelosEstado() {
        return vuelos.stream().map(this::toVueloDto).toList();
    }

    public synchronized List<EnvioDTO> getEnviosEstado() {
        return envios.stream().map(envio -> toEnvioDto(envio, false)).toList();
    }

    public synchronized Optional<EnvioDTO> getEnvioPorId(String idEnvio) {
        return envios.stream()
            .filter(envio -> envio.getIdEnvio().equals(idEnvio))
            .findFirst()
            .map(envio -> toEnvioDto(envio, true));
    }

    private void processDepartures() {
        LocalDate today = fechaSimulada.toLocalDate();
        Map<String, Envio> envioById = envios.stream().collect(Collectors.toMap(Envio::getIdEnvio, e -> e, (a, b) -> a));
        Map<String, Vuelo> vueloByCode = vuelos.stream().collect(Collectors.toMap(Vuelo::getCodigoVuelo, v -> v, (a, b) -> a));

        for (PlanDeViaje plan : planes) {
            Envio envio = envioById.get(plan.getIdEnvio());
            // Skip fully-delivered envios AND envios whose bags are already airborne on another leg
            if (envio == null || envio.getEstado() == EstadoEnvio.ENTREGADO) {
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

                // Only load bags that are sitting at the origin of this leg
                String legOrigin = vuelo.getOrigen();
                List<Maleta> maletasEnvio = maletas.stream()
                    .filter(m -> m.getIdEnvio().equals(envio.getIdEnvio()))
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
                }
            }
        }
    }

    private void processArrivals() {
        LocalDate today = fechaSimulada.toLocalDate();
        Map<String, Vuelo> vueloByCode = vuelos.stream().collect(Collectors.toMap(Vuelo::getCodigoVuelo, v -> v, (a, b) -> a));

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
                    log.info("Maleta {} arrived at {} estado={}",
                        maleta.getIdMaleta(),
                        maleta.getUbicacionActual(),
                        maleta.getEstado());
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

        for (Maleta maleta : maletas) {
            Envio envio = envioById.get(maleta.getIdEnvio());
            if (envio == null || maleta.getEstado() != EstadoMaleta.EN_ALMACEN) {
                continue;
            }

            if (envio.getAeropuertoDestino().equals(maleta.getUbicacionActual())) {
                maleta.setEstado(EstadoMaleta.ENTREGADA);
                delivered++;
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

        for (Envio envio : envios) {
            boolean allDelivered = maletas.stream()
                .filter(m -> m.getIdEnvio().equals(envio.getIdEnvio()))
                .allMatch(m -> m.getEstado() == EstadoMaleta.ENTREGADA);
            if (allDelivered) {
                envio.setEstado(EstadoEnvio.ENTREGADO);
            }
        }

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
        double probability = 0.05d + (random.nextDouble() * 0.03d);
        LocalDate today = fechaSimulada == null ? null : fechaSimulada.toLocalDate();
        Set<String> plannedToday = planes.stream()
            .flatMap(plan -> plan.getEscalas().stream())
            .filter(escala -> escala.getHoraSalidaEst() != null && today != null &&
                escala.getHoraSalidaEst().toLocalDate().equals(today))
            .map(Escala::getCodigoVuelo)
            .collect(Collectors.toSet());

        for (Vuelo vuelo : vuelos) {
            if (!plannedToday.contains(vuelo.getCodigoVuelo())) {
                continue;
            }
            if (vuelo.isCancelado()) {
                continue;
            }
            if (random.nextDouble() >= probability) {
                continue;
            }

            vuelo.setCancelado(true);
            Cancelacion cancelacion = Cancelacion.builder()
                .id("CAN-" + vuelo.getCodigoVuelo() + "-" + System.nanoTime())
                .codigoVuelo(vuelo.getCodigoVuelo())
                .fecha(fechaSimulada.toLocalDate())
                .hora(LocalTime.now())
                .motivo("Random disruption event")
                .build();
            cancelaciones.add(cancelacion);

            List<Maleta> affected = maletas.stream()
                .filter(m -> m.getEstado() == EstadoMaleta.EN_VUELO)
                .filter(m -> vuelo.getCodigoVuelo().equals(maletaVueloActual.get(m.getIdMaleta())))
                .toList();

            addOperationLog("Flight cancelled " + vuelo.getCodigoVuelo() + " with " + affected.size() + " affected maletas");
            replanificar(affected);
        }
    }

    private void updateWarehouseOccupation() {
        Map<String, Long> counts = maletas.stream()
            .filter(m -> m.getEstado() == EstadoMaleta.EN_ALMACEN)
            .collect(Collectors.groupingBy(Maleta::getUbicacionActual, Collectors.counting()));

        for (Aeropuerto aeropuerto : aeropuertos) {
            long count = counts.getOrDefault(aeropuerto.getCodigoIATA(), 0L);
            log.info("Recalc: airport {} has {} maletas EN_ALMACEN",
                aeropuerto.getCodigoIATA(), count);
            aeropuerto.setOcupacionActual((int) count);
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
        long totalEnvios = envios.size();
        long entregadosEnSla = envios.stream()
            .filter(e -> e.getEstado() == EstadoEnvio.ENTREGADO)
            .count();
        double cumplimientoSla = totalEnvios == 0 ? 0.0 : Math.round(entregadosEnSla * 1000.0 / totalEnvios) / 10.0;
        double ocupacionPromedio = aeropuertos.stream()
            .mapToDouble(a -> a.getCapacidadAlmacen() == 0 ? 0.0 : (a.getOcupacionActual() * 100.0d / a.getCapacidadAlmacen()))
            .average()
            .orElse(0.0d);

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
            .build();
    }

    private VueloDTO toVueloDto(Vuelo vuelo) {
        boolean usedByAnyPlan = planes.stream()
            .flatMap(plan -> plan.getEscalas().stream())
            .anyMatch(escala -> vuelo.getCodigoVuelo().equals(escala.getCodigoVuelo()));
        // Count maletas assigned to this flight across all active plans
        Set<String> planIdsUsingFlight = planes.stream()
            .filter(plan -> plan.getEscalas().stream()
                .anyMatch(e -> vuelo.getCodigoVuelo().equals(e.getCodigoVuelo())))
            .map(PlanDeViaje::getIdEnvio)
            .collect(Collectors.toSet());
        int maletasAsignadas = envios.stream()
            .filter(e -> planIdsUsingFlight.contains(e.getIdEnvio())
                && e.getEstado() != EstadoEnvio.ENTREGADO)
            .mapToInt(Envio::getCantidadMaletas)
            .sum();
        return VueloDTO.builder()
            .codigoVuelo(vuelo.getCodigoVuelo())
            .origen(vuelo.getOrigen())
            .destino(vuelo.getDestino())
            .tipo(vuelo.getTipo())
            .estado(resolveVueloEstado(vuelo))
            .cargaActual(vuelo.getCargaActual())
            .maletasAsignadas(maletasAsignadas)
            .capacidadTotal(vuelo.getCapacidadTotal())
            .fraction(resolveFraction(vuelo))
            .horaSalida(vuelo.getHoraSalida().toString())
            .horaLlegada(vuelo.getHoraLlegada().toString())
            .enUso(usedByAnyPlan)
            .build();
    }

    private EnvioDTO toEnvioDto(Envio envio, boolean includePlanDetail) {
        PlanDeViaje plan = planes.stream()
            .filter(p -> p.getIdEnvio().equals(envio.getIdEnvio()))
            .max(Comparator.comparingInt(PlanDeViaje::getVersion))
            .orElse(null);
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

    private String resolveVueloEstado(Vuelo vuelo) {
        if (vuelo.isCancelado()) {
            return "cancelado";
        }
        boolean usedByAnyPlan = planes.stream()
            .flatMap(plan -> plan.getEscalas().stream())
            .anyMatch(escala -> vuelo.getCodigoVuelo().equals(escala.getCodigoVuelo()));
        if (usedByAnyPlan && vuelo.getCargaActual() == 0 && diaActual > 1) {
            // Only mark completed if NOT scheduled for today or a future day.
            // avanzarDia() processes departures+arrivals in a single batch, so
            // cargaActual is already 0 for today's flights by the time the
            // frontend starts animating the day – they should still show as active.
            LocalDate today = fechaSimulada == null ? null : fechaSimulada.toLocalDate();
            boolean scheduledTodayOrFuture = today != null && planes.stream()
                .flatMap(plan -> plan.getEscalas().stream())
                .anyMatch(e -> vuelo.getCodigoVuelo().equals(e.getCodigoVuelo())
                    && e.getHoraSalidaEst() != null
                    && !e.getHoraSalidaEst().toLocalDate().isBefore(today));
            if (!scheduledTodayOrFuture) {
                return "completado";
            }
        }
        return "activo";
    }

    private double resolveFraction(Vuelo vuelo) {
        if (fechaSimulada == null) {
            return 0.0d;
        }
        LocalDate today = fechaSimulada.toLocalDate();
        // Find the planned departure for today so we can calculate mid-flight position.
        // fechaSimulada is always atStartOfDay(), so use the scheduled LocalTime directly.
        Optional<Escala> todayEscala = planes.stream()
            .flatMap(plan -> plan.getEscalas().stream())
            .filter(e -> vuelo.getCodigoVuelo().equals(e.getCodigoVuelo())
                && e.getHoraSalidaEst() != null
                && e.getHoraSalidaEst().toLocalDate().equals(today))
            .findFirst();
        if (todayEscala.isEmpty()) {
            return 0.0d;
        }
        // Use the wall-clock departure/arrival times; treat midnight-crossing flights correctly.
        long totalMinutes = Duration.between(vuelo.getHoraSalida(), vuelo.getHoraLlegada()).toMinutes();
        if (totalMinutes <= 0) {
            totalMinutes += 1440; // overnight flight
        }
        // Represent current sim time as minutes-since-midnight at the middle of the day (noon)
        // so that flights spread across the day are visible rather than all showing fraction=0.
        // The frontend also drives the clock animation, so returning 0.5 as a neutral default
        // is better than always 0. We return 0.5 to indicate "in progress today".
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
