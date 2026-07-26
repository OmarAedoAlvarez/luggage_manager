package com.tasf.backend.service;

import com.tasf.backend.algorithm.AirportTimeline;
import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.Escala;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.PlanningResult;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.dto.AirportInventoryDTO;
import com.tasf.backend.dto.EnvioDTO;
import com.tasf.backend.dto.ParteEnvioDTO;
import com.tasf.backend.dto.EnvioSummaryDTO;
import com.tasf.backend.dto.LiveStateDTO;
import com.tasf.backend.dto.LiveStateDTO.LiveAeropuertoDTO;
import com.tasf.backend.dto.LiveStateDTO.LiveVueloDTO;
import com.tasf.backend.dto.OpsEnvioRequestDTO;
import com.tasf.backend.dto.OpsReporteDTO;
import com.tasf.backend.entity.EnvioEntity;
import com.tasf.backend.entity.EscalaEntity;
import com.tasf.backend.entity.ItinerarioEntity;
import com.tasf.backend.ops.repository.OpsEnvioRepository;
import com.tasf.backend.ops.repository.OpsEscalaRepository;
import com.tasf.backend.ops.repository.OpsItinerarioRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final DataLoaderService dataLoaderService;
    private final OpsReferenceData opsReferenceData;
    private final PlanningService planningService;
    private final OpsEnvioRepository opsEnvioRepository;
    private final OpsItinerarioRepository itinerarioRepository;
    private final OpsEscalaRepository escalaRepository;

    private final ConcurrentHashMap<String, List<PlanDeViaje>> planesPorEnvio = new ConcurrentHashMap<>();
    // idEnvio -> bag count, captured at planning time (plans don't carry bag counts).
    private final ConcurrentHashMap<String, Integer> maletasPorEnvio = new ConcurrentHashMap<>();
    // idEnvio -> warehouse entry time (UTC), captured at planning time.
    private final ConcurrentHashMap<String, LocalDateTime> ingresoPorEnvio = new ConcurrentHashMap<>();
    // idEnvio -> orden of the current/next escala to process (1-based).
    private final ConcurrentHashMap<String, Integer> ordenActualByEnvio = new ConcurrentHashMap<>();
    // idEnvio -> UTC offset (hours) of origin airport at planning time.
    // All leg timestamps produced by the planner are in this local time, so the
    // scheduler must compare against it — not raw UTC — to avoid premature triggers.
    private final ConcurrentHashMap<String, Integer> husoPorEnvio = new ConcurrentHashMap<>();

    public OpsService(
            DataLoaderService dataLoaderService,
            OpsReferenceData opsReferenceData,
            PlanningService planningService,
            OpsEnvioRepository opsEnvioRepository,
            OpsItinerarioRepository itinerarioRepository,
            OpsEscalaRepository escalaRepository) {
        this.dataLoaderService = dataLoaderService;
        this.opsReferenceData = opsReferenceData;
        this.planningService = planningService;
        this.opsEnvioRepository = opsEnvioRepository;
        this.itinerarioRepository = itinerarioRepository;
        this.escalaRepository = escalaRepository;
    }

    // -------------------------------------------------------------------------
    // 1. getLiveState
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public LiveStateDTO getLiveState(LocalDateTime from) {
        // Warehouse occupation (cheap; reused by the lightweight occupancy endpoint).
        List<LiveAeropuertoDTO> aeropuertoDTOs = computeOccupation(from);

        // Maps and now-of-day needed for the flights section below.
        Map<String, Integer> husoByIata = new HashMap<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            husoByIata.put(a.getCodigoIATA(), a.getHuso());
        }
        // 2b. Collect flight codes currently used in planned routes
        Set<String> flightsInUso = new HashSet<>();
        for (List<PlanDeViaje> planList : planesPorEnvio.values()) {
            for (PlanDeViaje plan : planList) {
                if (plan.getEscalas() != null) {
                    for (Escala e : plan.getEscalas()) {
                        if (e.getCodigoVuelo() != null) {
                            flightsInUso.add(e.getCodigoVuelo());
                        }
                    }
                }
            }
        }

        // Flight loads. Each pending leg belongs to one of two buckets, decided by whether its
        // scheduled departure is in the future or already past relative to now (real UTC):
        //   - planificada: dep > now → bag hasn't boarded → belongs to an UPCOMING departure.
        //   - enTransito:  dep <= now → bag already left → belongs to the airborne occurrence.
        // A daily flight can be airborne today (today's occurrence) AND loaded for tomorrow at the
        // same time; the panel shows one row per flight code, so below we prefer the planificada
        // (upcoming) view when a flight carries not-yet-departed bags — that's the actionable one,
        // and it keeps a PLANIFICADO envío out of the "en vuelo" tab. Completed legs are skipped.
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, EnvioEntity> envioById = opsEnvioRepository.findAll().stream()
                .collect(Collectors.toMap(EnvioEntity::getIdPedido, e -> e, (a, b) -> a));
        Map<String, Integer> cargaPlanificada = new HashMap<>();
        Map<String, Integer> cargaEnTransito = new HashMap<>();
        for (List<PlanDeViaje> planList : planesPorEnvio.values()) {
            for (PlanDeViaje plan : planList) {
                EnvioEntity envio = envioById.get(plan.getIdEnvio());
                if (envio == null) continue;
                if (envio.getEstado().equals("ENTREGADO") || envio.getEstado().equals("CANCELADO")) continue;
                if (plan.getEscalas() == null) continue;
                int qty = plan.getCantidadMaletas() > 0 ? plan.getCantidadMaletas() : envio.getCantidadMaletas();
                for (Escala e : plan.getEscalas()) {
                    String code = e.getCodigoVuelo();
                    if (code == null || e.getHoraSalidaEst() == null || e.isCompletada()) continue;
                    if (e.getHoraSalidaEst().isAfter(nowUtc)) {
                        cargaPlanificada.merge(code, qty, Integer::sum);
                    } else {
                        cargaEnTransito.merge(code, qty, Integer::sum);
                    }
                }
            }
        }

        // 3. Show only flights currently airborne. Flight times are a daily-repeating
        //    schedule (time-of-day, no date), so an overnight flight (dep > arr) is
        //    airborne when now is past departure OR before arrival.
        List<LiveVueloDTO> vueloDTOs = new ArrayList<>();
        for (Vuelo v : opsReferenceData.getVuelos()) {
            if (dataLoaderService.isFlightCancelledForSession(v.getCodigoVuelo())) {
                continue;
            }
            String code = v.getCodigoVuelo();
            FlightOccurrence occ = occurrenceOf(from, v.getHoraSalida(), v.getHoraLlegada());
            boolean enUso = flightsInUso.contains(code);

            int planificada = cargaPlanificada.getOrDefault(code, 0);
            int enTransito = cargaEnTransito.getOrDefault(code, 0);

            // Prefer showing the live airborne occurrence if the flight is currently in the air.
            // This prevents airplanes from disappearing from the map when future shipments are planned.
            // Once the flight lands, it will show as upcoming with its planned load.
            boolean inFlight = occ.inFlight();
            int cargaActual;
            
            if (inFlight) {
                cargaActual = enTransito;
            } else if (planificada > 0) {
                cargaActual = planificada;
            } else {
                cargaActual = 0;
            }

            // Solo enviar vuelos que están volando AHORA (inFlight) o que van a salir después (upcoming)
            if (!inFlight && !occ.upcoming() && planificada == 0) {
                continue;
            }

            double fraction = 0.0;
            if (occ.durationMin() > 0 && inFlight) {
                long elapsed = Duration.between(occ.depUtc(), from).toMinutes();
                fraction = Math.max(0.0, Math.min(1.0, (double) elapsed / occ.durationMin()));
            }

            vueloDTOs.add(LiveVueloDTO.builder()
                    .codigoVuelo(code)
                    .origen(v.getOrigen())
                    .destino(v.getDestino())
                    .horaSalida(v.getHoraSalida().format(TIME_FMT))
                    .horaLlegada(v.getHoraLlegada().format(TIME_FMT))
                    .tipo(v.getTipo())
                    .capacidadTotal(v.getCapacidadTotal())
                    .cargaActual(cargaActual)
                    .fraction(fraction)
                    .husOrigen(husoByIata.get(v.getOrigen()))
                    .husDestino(husoByIata.get(v.getDestino()))
                    .enUso(enUso)
                    .inFlight(inFlight)
                    .cancelacionProgramada(dataLoaderService.isFlightCancellationProgramadaForSession(v.getCodigoVuelo()))
                    .build());
        }

        // 4. Build cancelaciones list from session-level cancellations
        Map<String, Vuelo> vueloByCode = new HashMap<>();
        for (Vuelo v : opsReferenceData.getVuelos()) {
            vueloByCode.put(v.getCodigoVuelo(), v);
        }
        List<LiveStateDTO.LiveCancelacionDTO> cancelacionDTOs = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(7);
        for (Map.Entry<String, LocalDate> entry : dataLoaderService.getSessionCancelledFlightDates().entrySet()) {
            String code = entry.getKey();
            LocalDate cancelDate = entry.getValue();
            // Include cancellations from the last 7 days + tomorrow (programmed)
            if (cancelDate.isBefore(cutoff) || cancelDate.isAfter(today.plusDays(1))) continue;
            Vuelo v = vueloByCode.get(code);
            String motivo;
            if (cancelDate.equals(today)) {
                motivo = "Cancelación manual (hoy)";
            } else if (cancelDate.equals(today.plusDays(1))) {
                motivo = "Cancelación programada (mañana)";
            } else {
                motivo = "Cancelado el " + cancelDate;
            }
            cancelacionDTOs.add(LiveStateDTO.LiveCancelacionDTO.builder()
                    .id(code + "-" + cancelDate)
                    .codigoVuelo(code)
                    .origen(v != null ? v.getOrigen() : "?")
                    .destino(v != null ? v.getDestino() : "?")
                    .horaSalida(v != null ? v.getHoraSalida().format(TIME_FMT) : null)
                    .horaLlegada(v != null ? v.getHoraLlegada().format(TIME_FMT) : null)
                    .tipo(v != null ? v.getTipo() : null)
                    .capacidadTotal(v != null ? v.getCapacidadTotal() : 0)
                    .fecha(cancelDate.toString())
                    .motivo(motivo)
                    .maletasAfectadas(0)
                    .build());
        }

        return LiveStateDTO.builder()
                .aeropuertos(aeropuertoDTOs)
                .vuelos(vueloDTOs)
                .cancelaciones(cancelacionDTOs)
                .build();
    }

    /** A flight's daily schedule is time-of-day only (UTC). Given the current instant, this is the
     *  occurrence to display: the one still airborne now, or the next upcoming one. Comparing full
     *  instants (not minute-of-day) is what makes it correct across midnight — e.g. now=23:33 local
     *  but the flight's next departure is tomorrow morning, which is upcoming, not "already gone". */
    static FlightOccurrence occurrenceOf(LocalDateTime fromUtc, LocalTime depUtc, LocalTime arrUtc) {
        int durationMin = Math.floorMod(
                (arrUtc.getHour() * 60 + arrUtc.getMinute()) - (depUtc.getHour() * 60 + depUtc.getMinute()), 1440);
        LocalDateTime dep = fromUtc.toLocalDate().atTime(depUtc);
        if (dep.plusMinutes(durationMin).isBefore(fromUtc)) {
            dep = dep.plusDays(1); // today's occurrence already landed → show the next one
        }
        boolean inFlight = !fromUtc.isBefore(dep) && fromUtc.isBefore(dep.plusMinutes(durationMin));
        boolean upcoming = fromUtc.isBefore(dep);
        return new FlightOccurrence(dep, durationMin, inFlight, upcoming);
    }

    record FlightOccurrence(LocalDateTime depUtc, int durationMin, boolean inFlight, boolean upcoming) {}

    @org.springframework.transaction.annotation.Transactional("opsTransactionManager")
    public void cancelFlight(String codigoVuelo, String aplicaDesde) {
        dataLoaderService.cancelFlightForSession(codigoVuelo, aplicaDesde);

        // Reroute around the cancelled flight: any shipment whose current route uses it goes
        // back to PENDIENTE so planificar() re-plans it (planificar now skips cancelled flights).
        Set<String> afectados = new HashSet<>();
        for (Map.Entry<String, List<PlanDeViaje>> en : planesPorEnvio.entrySet()) {
            for (PlanDeViaje p : en.getValue()) {
                if (p.getEscalas() != null && p.getEscalas().stream()
                        .anyMatch(e -> codigoVuelo.equals(e.getCodigoVuelo()))) {
                    afectados.add(en.getKey());
                    break;
                }
            }
        }
        if (afectados.isEmpty()) return;

        List<EnvioEntity> entidades = new ArrayList<>();
        for (String id : afectados) {
            opsEnvioRepository.findByIdPedido(id).ifPresent(e -> {
                e.setEstado("PENDIENTE");
                entidades.add(e);
            });
        }
        opsEnvioRepository.saveAll(entidades);
        planificar();
    }

    public void clearCancellations() {
        dataLoaderService.clearSessionCancellations();
    }

    /** Reset any PLANIFICADO shipment whose stored plan uses a flight that no longer exists
     *  (deleted directly from the DB, bypassing cancel-flight) back to PENDIENTE. */
    private void invalidatePlansWithMissingFlights() {
        opsReferenceData.reload();
        Set<String> vuelosVigentes = opsReferenceData.getVuelos().stream()
                .map(Vuelo::getCodigoVuelo)
                .collect(Collectors.toSet());
        List<EnvioEntity> aReplanificar = new ArrayList<>();
        for (EnvioEntity e : opsEnvioRepository.findAllByEstado("PLANIFICADO")) {
            List<PlanDeViaje> planes = planesPorEnvio.get(e.getIdPedido());
            if (planes == null) planes = loadPlansFromDb(e.getIdPedido());
            boolean usaVueloInexistente = planes.stream()
                    .filter(p -> p.getEscalas() != null)
                    .flatMap(p -> p.getEscalas().stream())
                    .anyMatch(esc -> esc.getCodigoVuelo() != null
                            && !vuelosVigentes.contains(esc.getCodigoVuelo()));
            if (usaVueloInexistente) {
                e.setEstado("PENDIENTE");
                planesPorEnvio.remove(e.getIdPedido());
                aReplanificar.add(e);
            }
        }
        if (!aReplanificar.isEmpty()) {
            opsEnvioRepository.saveAll(aReplanificar);
            log.info("Revalidación: {} envíos PLANIFICADO → PENDIENTE por vuelo inexistente",
                    aReplanificar.size());
        }
    }

    // -------------------------------------------------------------------------
    // 1b. computeOccupation — warehouse occupancy only (no flights). Cheap enough
    //     to poll frequently for a real-time airport view.
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public List<LiveAeropuertoDTO> computeOccupation(LocalDateTime from) {
        opsReferenceData.reload();

        // A bag is physically in the warehouse in both PENDIENTE (no route yet) and
        // PLANIFICADO (has a route, awaiting its scheduled departure) — it only leaves
        // the count once procesarSalidas() flips it to EN_TRANSITO. Count both.
        Map<String, Long> pendingByIata = new HashMap<>();
        for (Object[] row : opsEnvioRepository.sumAllMaletasPendientesByAeropuerto()) {
            pendingByIata.put((String) row[0], ((Number) row[1]).longValue());
        }

        List<LiveAeropuertoDTO> aeropuertoDTOs = new ArrayList<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            long pending = pendingByIata.getOrDefault(a.getCodigoIATA(), 0L);
            int maletasPendientes = (int) Math.min(pending, Integer.MAX_VALUE);
            int capacidad = a.getCapacidadAlmacen();

            double ocupacionPct = 0.0;
            if (capacidad > 0) {
                ocupacionPct = (double) maletasPendientes / capacidad * 100.0;
            }
            ocupacionPct = Math.max(0.0, Math.min(100.0, ocupacionPct));

            String semaforo;
            if (ocupacionPct < 70.0) {
                semaforo = "GREEN";
            } else if (ocupacionPct < 90.0) {
                semaforo = "AMBER";
            } else {
                semaforo = "RED";
            }

            aeropuertoDTOs.add(LiveAeropuertoDTO.builder()
                    .codigoIATA(a.getCodigoIATA())
                    .nombre(a.getNombre())
                    .ciudad(a.getCiudad())
                    .continente(a.getContinente())
                    .lat(a.getLat())
                    .lng(a.getLng())
                    .huso(a.getHuso())
                    .capacidadAlmacen(capacidad)
                    .maletasPendientes(maletasPendientes)
                    .ocupacionPct(ocupacionPct)
                    .semaforo(semaforo)
                    .build());
        }
        return aeropuertoDTOs;
    }

    // -------------------------------------------------------------------------
    // 2. addEnvio
    // -------------------------------------------------------------------------

    @Transactional("opsTransactionManager")
    public EnvioEntity addEnvio(OpsEnvioRequestDTO dto) {
        // Parse ISO-8601 with offset and convert to UTC
        OffsetDateTime offsetDt = OffsetDateTime.parse(dto.getFechaHoraIngreso());
        LocalDateTime fechaUtc = offsetDt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

        // Calculate SLA: 1 if same continent, 2 if different
        Map<String, String> continentByIata = new HashMap<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            continentByIata.put(a.getCodigoIATA(), a.getContinente());
        }
        String continenteOrigen = continentByIata.get(dto.getIataOrigen());
        String continenteDestino = continentByIata.get(dto.getIataDestino());
        int sla = (continenteOrigen != null && continenteOrigen.equals(continenteDestino)) ? 1 : 2;

        // Generate unique idPedido
        String idPedido = "OPS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        EnvioEntity entity = EnvioEntity.builder()
                .idPedido(idPedido)
                .codigoAerolinea(validateCodigoAerolinea(dto.getCodigoAerolinea()))
                .iataOrigen(dto.getIataOrigen())
                .iataDestino(dto.getIataDestino())
                .cantidadMaletas(dto.getCantidadMaletas())
                .fechaHoraIngreso(fechaUtc)
                .sla(sla)
                .estado("PENDIENTE")
                .build();

        return opsEnvioRepository.save(entity);
    }

    // codigo_aerolinea is varchar(10); reject rather than let it truncate silently.
    private String validateCodigoAerolinea(String codigoAerolinea) {
        if (codigoAerolinea != null && codigoAerolinea.length() > 10) {
            throw new IllegalArgumentException(
                    "codigoAerolinea excede 10 caracteres: " + codigoAerolinea);
        }
        return codigoAerolinea;
    }

    // -------------------------------------------------------------------------
    // 3. planificar
    // -------------------------------------------------------------------------

    @org.springframework.transaction.annotation.Transactional("opsTransactionManager")
    public PlanningResult planificar() {
        // A flight deleted straight from the DB (not via cancel-flight) leaves PLANIFICADO plans
        // pointing at a flight that no longer exists. Reset those to PENDIENTE so they replan below.
        invalidatePlansWithMissingFlights();

        List<EnvioEntity> pendientes = opsEnvioRepository.findAllPendientesOrdenados();
        if (pendientes.isEmpty()) {
            log.info("No PENDIENTE envios to plan.");
            return PlanningResult.builder()
                    .planes(Collections.emptyList())
                    .enviosSinRuta(Collections.emptyList())
                    .build();
        }

        Map<String, Integer> husoByIata = new HashMap<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            husoByIata.put(a.getCodigoIATA(), a.getHuso());
        }
        List<Envio> domainEnvios = pendientes.stream()
                .map(this::toDomain)
                .toList();

        // Ops has no rolling day loop — it plans on demand. K=1 (Sa=1min default) keeps the
        // Sc window at 1min, so the currentTimeUtc floor below stays effectively "now": each
        // shipment gets routed immediately instead of waiting out a multi-hour batch window.
        int saMinutos = 1;
        int k = 1;
        LocalDateTime ahora = LocalDateTime.now(ZoneOffset.UTC);
        ParametrosSimulacion params = ParametrosSimulacion.builder()
                .algoritmo("SIMULATED_ANNEALING")
                .minutosEscalaMinima(10)
                .minutosRecogidaDestino(15)
                .minutosPreparacionOrigen(10)
                .umbralSemaforoVerde(60)
                .umbralSemaforoAmbar(85)
                .fechaInicio(LocalDate.now(ZoneOffset.UTC))
                .saMinutos(saMinutos)
                .k(k)
                .build();
        // Same Sc-window floor mechanism SimulationEngine uses (RoutePlannerSupport honours
        // currentTimeUtc as earliest-departure floor) — no flight assigned before this window closes.
        params.setCurrentTimeUtc(ahora.plusMinutes(params.getScMinutos()));

        // Never route bags onto a flight cancelled this session.
        Set<String> vuelosCancelados = dataLoaderService.getSessionCancelledFlightDates().keySet();
        List<Vuelo> vuelosActivos = opsReferenceData.getVuelos().stream()
                .filter(v -> !vuelosCancelados.contains(v.getCodigoVuelo()))
                .toList();

        // Seed flight loads with bags already committed by shipments we are NOT re-planning here.
        // planificar() only replans PENDIENTE envíos; the PLANIFICADO ones keep their flights, so
        // without this the planner sees every flight as empty and can over-fill one that already
        // carries bags (e.g. a split part), producing 180/150 on a 150-seat flight. The key must
        // match SimulatedAnnealingAlgorithm.flightDayKey exactly: "codigoVuelo|departureLocalDate",
        // and Escala.horaSalidaEst is precisely the leg departure the planner used (RouteCandidate).
        Set<String> replanIds = pendientes.stream()
                .map(EnvioEntity::getIdPedido)
                .collect(Collectors.toSet());
        Map<String, Integer> flightLoadsSeed = new HashMap<>();
        for (Map.Entry<String, List<PlanDeViaje>> en : planesPorEnvio.entrySet()) {
            if (replanIds.contains(en.getKey())) continue; // being re-planned — its old plan is discarded
            for (PlanDeViaje plan : en.getValue()) {
                if (plan.getEscalas() == null) continue;
                int qty = plan.getCantidadMaletas() > 0
                        ? plan.getCantidadMaletas()
                        : maletasPorEnvio.getOrDefault(en.getKey(), 0);
                for (Escala e : plan.getEscalas()) {
                    if (e.getCodigoVuelo() == null || e.getHoraSalidaEst() == null || e.isCompletada()) continue;
                    String key = e.getCodigoVuelo() + "|" + e.getHoraSalidaEst().toLocalDate();
                    flightLoadsSeed.merge(key, qty, Integer::sum);
                }
            }
        }

        // Warehouse timeline stays fresh (same as before) — only flight capacity is seeded, which
        // is the constraint the over-fill bug violated. planificarLote honours the seeded loads.
        PlanningResult result = planningService.planificarLote(
                domainEnvios,
                vuelosActivos,
                opsReferenceData.getAeropuertos(),
                params,
                new AirportTimeline(),
                flightLoadsSeed);

        // Store each plan in the in-memory map, plus its bag count and entry time for the drain.
        for (EnvioEntity e : pendientes) {
            maletasPorEnvio.put(e.getIdPedido(), e.getCantidadMaletas());
            ingresoPorEnvio.put(e.getIdPedido(), e.getFechaHoraIngreso());
            ordenActualByEnvio.remove(e.getIdPedido()); // reset leg progress on re-plan
            // Capture origin timezone so the scheduler compares against the same local
            // time reference that the planner used to build horaSalidaEst/horaLlegadaEst.
            husoPorEnvio.put(e.getIdPedido(), husoByIata.getOrDefault(e.getIataOrigen(), 0));
            planesPorEnvio.remove(e.getIdPedido()); // clear old plans before re-planning
        }
        for (PlanDeViaje plan : result.getPlanes()) {
            planesPorEnvio.computeIfAbsent(plan.getIdEnvio(), id -> new ArrayList<>()).add(plan);
        }
        // Clear stale persisted itinerarios for EVERY pedido being planned — including ones
        // that end up sin-ruta — so loadPlanFromDb can't resurrect a ghost plan for them.
        deleteItinerariosForPedidos(pendientes.stream()
                .map(EnvioEntity::getIdPedido)
                .collect(Collectors.toSet()));
        persistOpsPlans(result.getPlanes());

        // Mark envíos that got a route as PLANIFICADO (in warehouse, awaiting departure).
        // Ones left out of result.getPlanes() (enviosSinRuta) stay PENDIENTE — no plan yet.
        Set<String> plannedIds = result.getPlanes().stream()
                .map(PlanDeViaje::getIdEnvio)
                .collect(Collectors.toSet());
        for (EnvioEntity e : pendientes) {
            if (plannedIds.contains(e.getIdPedido())) {
                e.setEstado("PLANIFICADO");
            }
        }
        opsEnvioRepository.saveAll(pendientes);

        log.info("Planned {} envios; {} without route", result.getPlanes().size(),
                result.getEnviosSinRuta().size());
        return result;
    }

    // -------------------------------------------------------------------------
    // 4. getEnvios
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public List<EnvioDTO> getEnvios() {
        return opsEnvioRepository.findAllByOrderByFechaHoraIngresoDesc().stream()
                .map(e -> {
                    List<PlanDeViaje> plans = planesPorEnvio.get(e.getIdPedido());
                    PlanDeViaje plan = (plans != null && !plans.isEmpty()) ? plans.get(0) : null;
                    if (plan == null) plan = loadPlanFromDb(e.getIdPedido());
                    return toDto(e, plan);
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // 5. getPlan
    // -------------------------------------------------------------------------

    public PlanDeViaje getPlan(String idPedido) {
        List<PlanDeViaje> plans = planesPorEnvio.get(idPedido);
        return (plans != null && !plans.isEmpty()) ? plans.get(0) : null;
    }

    public Optional<EnvioDTO> getEnvioById(String idPedido) {
        return opsEnvioRepository.findByIdPedido(idPedido).map(ent -> {
            List<PlanDeViaje> planList = planesPorEnvio.get(idPedido);
            if (planList == null || planList.isEmpty()) {
                planList = loadPlansFromDb(idPedido);
            }
            PlanDeViaje plan = (planList != null && !planList.isEmpty()) ? planList.get(0) : null;
            EnvioDTO dto = toDto(ent, plan);
            dto.setPartes(buildPartes(ent, planList));
            return dto;
        });
    }

    /**
     * Desglose de partes de un envío (split). Cada parte = un plan/versión, ordenadas por versión,
     * con un rango contiguo de maletas asignado (parte 1: 1..q1, parte 2: q1+1..q1+q2, …). El
     * envío conserva un solo id/código; las partes solo reparten el rango de maletas y la ruta.
     */
    private List<ParteEnvioDTO> buildPartes(EnvioEntity ent, List<PlanDeViaje> planList) {
        if (planList == null || planList.isEmpty()) return null;
        List<PlanDeViaje> ordenados = planList.stream()
                .sorted(Comparator.comparingInt(PlanDeViaje::getVersion))
                .toList();
        int total = ordenados.size();
        // Si por alguna razón las cantidades por-plan no están (planes viejos = 0), repartir el
        // total del envío en la primera parte para no romper los rangos.
        int cursor = 0;
        List<ParteEnvioDTO> partes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            PlanDeViaje plan = ordenados.get(i);
            int qty = plan.getCantidadMaletas() > 0 ? plan.getCantidadMaletas()
                    : (total == 1 ? ent.getCantidadMaletas() : 0);
            int desde = cursor + 1;
            int hasta = cursor + qty;
            cursor = hasta;
            partes.add(ParteEnvioDTO.builder()
                    .parteNo(i + 1)
                    .totalPartes(total)
                    .cantidadMaletas(qty)
                    .maletaDesde(desde)
                    .maletaHasta(hasta)
                    .planResumen(buildPlanResumen(ent.getIataOrigen(), ent.getIataDestino(), plan))
                    .planDetalle(plan)
                    .build());
        }
        return partes;
    }

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public List<EnvioDTO> getEnviosByFlight(String codigoVuelo) {
        // Find expected departure date for this flight (same logic as getLiveState)
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate expectedDate = null;

        Optional<Vuelo> optVuelo = opsReferenceData.getVuelos().stream()
            .filter(v -> v.getCodigoVuelo().equals(codigoVuelo))
            .findFirst();

        boolean isFlightAirborne = false;
        if (optVuelo.isPresent()) {
            Vuelo v = optVuelo.get();
            Map<String, Integer> husoByIata = new HashMap<>();
            for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
                husoByIata.put(a.getCodigoIATA(), a.getHuso());
            }
            int husoOrigenVuelo = husoByIata.getOrDefault(v.getOrigen(), 0);
            FlightOccurrence occ = occurrenceOf(nowUtc, v.getHoraSalida(), v.getHoraLlegada());
            isFlightAirborne = occ.inFlight();
            expectedDate = occ.depUtc().plusHours(husoOrigenVuelo).toLocalDate();
        }

        Set<String> envioIds = new java.util.HashSet<>();
        // Iterate ALL plans (every split part), matching the occupancy calc in getLiveState.
        // Only plan[0] would miss a split part routed on a different plan/version.
        for (List<PlanDeViaje> planes : planesPorEnvio.values()) {
            for (PlanDeViaje p : planes) {
                if (p.getEscalas() == null) continue;
                for (Escala e : p.getEscalas()) {
                    if (codigoVuelo.equals(e.getCodigoVuelo())) {
                        // Mirror getLiveState: any pending (non-completed) leg on this flight
                        // counts, regardless of the leg's calendar date — the displayed occurrence
                        // may differ from the leg's date for a still-airborne daily flight.
                        if (expectedDate != null && !e.isCompletada()) {
                            if (isFlightAirborne) {
                                if (e.getHoraSalidaEst() != null && !e.getHoraSalidaEst().isAfter(nowUtc)) {
                                    envioIds.add(p.getIdEnvio());
                                }
                            } else {
                                if (e.getHoraSalidaEst() != null && e.getHoraSalidaEst().isAfter(nowUtc)) {
                                    envioIds.add(p.getIdEnvio());
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }
        
        if (envioIds.isEmpty()) return java.util.Collections.emptyList();
        
        List<EnvioDTO> result = new ArrayList<>();
        for (String idPedido : envioIds) {
            opsEnvioRepository.findByIdPedido(idPedido).ifPresent(ent -> {
                List<PlanDeViaje> planList = planesPorEnvio.get(ent.getIdPedido());
                EnvioDTO dto = toDto(ent, planList.get(0));
                dto.setPartes(buildPartes(ent, planList));
                result.add(dto);
            });
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 6b. getEnviosEntregados — envíos delivered in the last N hours (real UTC).
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public List<EnvioDTO> getEnviosEntregados(int horas) {
        int clampedHoras = Math.min(Math.max(horas, 1), 24);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(clampedHoras);
        return opsEnvioRepository
                .findByEstadoAndFechaEntregaAfterOrderByFechaEntregaDesc("ENTREGADO", since)
                .stream()
                .map(e -> {
                    List<PlanDeViaje> plans = planesPorEnvio.get(e.getIdPedido());
                    PlanDeViaje plan = (plans != null && !plans.isEmpty()) ? plans.get(0) : loadPlanFromDb(e.getIdPedido());
                    return toDto(e, plan);
                })
                .toList();
    }

    private EnvioDTO toDto(EnvioEntity ent, PlanDeViaje plan) {
        String fechaSalidaPrimerVuelo = null;
        String fechaLlegadaUltimoVuelo = null;
        if (plan != null && plan.getEscalas() != null && !plan.getEscalas().isEmpty()) {
            List<Escala> escalas = plan.getEscalas();
            if (escalas.get(0).getHoraSalidaEst() != null) {
                fechaSalidaPrimerVuelo = escalas.get(0).getHoraSalidaEst().toString();
            }
            if (escalas.get(escalas.size() - 1).getHoraLlegadaEst() != null) {
                fechaLlegadaUltimoVuelo = escalas.get(escalas.size() - 1).getHoraLlegadaEst().toString();
            }
        }
        
        // SLA deadline = ingreso + sla days (both UTC, same frame the planner uses). Remaining
        // time counts down to that deadline from delivery (if entregado) else now.
        String fechaLimiteSla = null;
        String tiempoRestante = null;
        if (ent.getFechaHoraIngreso() != null && ent.getSla() > 0) {
            LocalDateTime deadline = ent.getFechaHoraIngreso().plusDays(ent.getSla());
            fechaLimiteSla = deadline.toString();
            LocalDateTime ref = ent.getFechaEntrega() != null
                    ? ent.getFechaEntrega()
                    : LocalDateTime.now(ZoneOffset.UTC);
            tiempoRestante = formatDuracionRestante(Duration.between(ref, deadline).toMinutes());
        }

        return EnvioDTO.builder()
            .idEnvio(ent.getIdPedido())
            .codigoAerolinea(ent.getCodigoAerolinea())
            .aeropuertoOrigen(ent.getIataOrigen())
            .aeropuertoDestino(ent.getIataDestino())
            .cantidadMaletas(ent.getCantidadMaletas())
            .estado(ent.getEstado())
            .sla(ent.getSla())
            .fechaHoraIngreso(ent.getFechaHoraIngreso() != null ? ent.getFechaHoraIngreso().toString() : null)
            .fechaSalidaPrimerVuelo(fechaSalidaPrimerVuelo)
            .fechaLlegadaUltimoVuelo(fechaLlegadaUltimoVuelo)
            .fechaLimiteSla(fechaLimiteSla)
            .tiempoRestante(tiempoRestante)
            .planResumen(buildPlanResumen(ent.getIataOrigen(), ent.getIataDestino(), plan))
            .fechaEntrega(ent.getFechaEntrega() != null ? ent.getFechaEntrega().toString() : null)
            .planDetalle(plan)
            .build();
    }

    /** "1d 4h 30m" style remaining-time label; "Vencido" once the deadline has passed. */
    private String formatDuracionRestante(long totalMin) {
        if (totalMin < 0) return "Vencido";
        long dias = totalMin / 1440;
        long horas = (totalMin % 1440) / 60;
        long minutos = totalMin % 60;
        StringBuilder sb = new StringBuilder();
        if (dias > 0) sb.append(dias).append("d ");
        if (dias > 0 || horas > 0) sb.append(horas).append("h ");
        sb.append(minutos).append("m");
        return sb.toString();
    }

    private PlanDeViaje loadPlanFromDb(String idPedido) {
        List<PlanDeViaje> planes = loadPlansFromDb(idPedido);
        return planes.isEmpty() ? null : planes.get(0);
    }

    /**
     * Rehidrata los mapas en memoria desde la BD al arrancar. Sin esto, tras un reinicio del
     * backend planesPorEnvio queda vacío y las vistas por-vuelo (ocupación, getEnviosByFlight)
     * muestran 0% aunque los itinerarios sigan persistidos. Corre tras el @PostConstruct de
     * DataLoaderService/OpsReferenceData, así opsReferenceData ya está poblado.
     * ponytail: no restaura ordenActualByEnvio (progreso de tramo); escala.completada persistido
     * cubre el detalle. Añadir persistencia de orden si el drain post-reinicio se desincroniza.
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional(value = "opsTransactionManager", readOnly = true)
    public void rehydratePlansFromDb() {
        Set<String> pedidos = itinerarioRepository.findAll().stream()
                .map(ItinerarioEntity::getIdPedido)
                .collect(Collectors.toSet());
        if (pedidos.isEmpty()) return;
        Map<String, Integer> husoByIata = new HashMap<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            husoByIata.put(a.getCodigoIATA(), a.getHuso());
        }
        int loaded = 0;
        for (String idPedido : pedidos) {
            List<PlanDeViaje> planes = loadPlansFromDb(idPedido);
            if (planes.isEmpty()) continue;
            planesPorEnvio.put(idPedido, planes);
            opsEnvioRepository.findByIdPedido(idPedido).ifPresent(e -> {
                husoPorEnvio.put(idPedido, husoByIata.getOrDefault(e.getIataOrigen(), 0));
                maletasPorEnvio.put(idPedido, e.getCantidadMaletas());
                ingresoPorEnvio.put(idPedido, e.getFechaHoraIngreso());
            });
            loaded++;
        }
        log.info("Rehydrated {} plan(s) from DB into memory at startup", loaded);
    }

    /** Todos los planes activos (partes del split) de un envío, ordenados por versión. */
    private List<PlanDeViaje> loadPlansFromDb(String idPedido) {
        return itinerarioRepository.findByIdPedidoAndEsActivo(idPedido, true).stream()
            .sorted(Comparator.comparingInt(ItinerarioEntity::getVersion))
            .map(it -> {
                List<Escala> escalas = escalaRepository.findByIdItinerarioOrderByOrden(it.getIdItinerario())
                    .stream()
                    .map(e -> Escala.builder()
                        .orden(e.getOrden())
                        .codigoAeropuerto(e.getIataEscala())
                        .codigoVuelo(e.getCodigoVuelo())
                        .horaSalidaEst(e.getHoraSalidaEst())
                        .horaLlegadaEst(e.getHoraLlegadaEst())
                        .completada(e.isCompletada())
                        .build())
                    .collect(Collectors.toList());
                PlanDeViaje plan = PlanDeViaje.builder()
                    .idPlan(it.getIdItinerario())
                    .idEnvio(idPedido)
                    .version(it.getVersion())
                    .esActivo(true)
                    .escalas(escalas)
                    .fechaCreacion(it.getFechaCreacion())
                    .build();
                plan.setCantidadMaletas(it.getCantidadMaletas());
                return plan;
            })
            .collect(Collectors.toList());
    }

    /**
     * Delete every persisted itinerario (and its escalas) for the given pedidos. Called before
     * (re)planning so no stale plan survives — whether the shipment gets a shorter new route
     * (leftover higher-orden escalas would otherwise survive the saveAll upsert, producing
     * phantom non-chaining legs) or ends up sin-ruta this time (an old itinerario would still
     * be served by loadPlanFromDb as a ghost partial route).
     */
    @org.springframework.transaction.annotation.Transactional("opsTransactionManager")
    public void deleteItinerariosForPedidos(Collection<String> pedidos) {
        if (pedidos.isEmpty()) return;
        List<ItinerarioEntity> previos = itinerarioRepository.findByIdPedidoIn(pedidos);
        if (previos.isEmpty()) return;
        List<String> idsItinerarioPrevios = previos.stream()
                .map(ItinerarioEntity::getIdItinerario)
                .toList();
        escalaRepository.deleteByIdItinerarioIn(idsItinerarioPrevios);
        itinerarioRepository.deleteAll(previos);
        // Flush deletes before any re-insert that may reuse the same PKs.
        itinerarioRepository.flush();
    }

    @org.springframework.transaction.annotation.Transactional("opsTransactionManager")
    public void persistOpsPlans(List<PlanDeViaje> planes) {
        List<ItinerarioEntity> itinerarios = new ArrayList<>();
        List<EscalaEntity> escalas = new ArrayList<>();
        for (PlanDeViaje plan : planes) {
            String idIt = plan.getIdEnvio() + "-ops-v" + plan.getVersion();
            itinerarios.add(ItinerarioEntity.builder()
                .idItinerario(idIt)
                .idPedido(plan.getIdEnvio())
                .version(plan.getVersion())
                .esActivo(true)
                .fechaCreacion(LocalDateTime.now())
                .cantidadMaletas(plan.getCantidadMaletas())
                .build());
            List<Escala> esc = plan.getEscalas();
            for (int i = 0; i < esc.size(); i++) {
                Escala e = esc.get(i);
                escalas.add(EscalaEntity.builder()
                    .idItinerario(idIt)
                    .orden(i + 1)
                    .codigoVuelo(e.getCodigoVuelo())
                    .iataEscala(e.getCodigoAeropuerto())
                    .horaSalidaEst(e.getHoraSalidaEst())
                    .horaLlegadaEst(e.getHoraLlegadaEst())
                    .completada(e.isCompletada())
                    .build());
            }
        }
        itinerarioRepository.saveAll(itinerarios);
        escalaRepository.saveAll(escalas);
    }

    // -------------------------------------------------------------------------
    // 6. getReporte
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public OpsReporteDTO getReporte() {
        List<EnvioEntity> all = opsEnvioRepository.findAll();
        int total = all.size();
        int pendientes = 0;
        int entregados = 0;
        int violados = 0;
        int totalMaletas = 0;

        for (EnvioEntity e : all) {
            totalMaletas += e.getCantidadMaletas();
            switch (e.getEstado()) {
                case "PENDIENTE", "PLANIFICADO" -> pendientes++;
                case "ENTREGADO" -> entregados++;
                case "VIOLADO" -> violados++;
                default -> { /* other states not counted separately */ }
            }
        }

        double porcentaje = 0.0;
        if (total > 0) {
            porcentaje = Math.round(((double) entregados / total * 100.0) * 10.0) / 10.0;
        }

        return OpsReporteDTO.builder()
                .totalEnvios(total)
                .enviosPendientes(pendientes)
                .enviosEntregados(entregados)
                .enviosViolados(violados)
                .totalMaletas(totalMaletas)
                .porcentajeCumplimientoSla(porcentaje)
                .generadoEn(LocalDateTime.now(ZoneOffset.UTC).toString())
                .build();
    }

    // -------------------------------------------------------------------------
    // 7. batchSave
    // -------------------------------------------------------------------------

    @Transactional("opsTransactionManager")
    public List<EnvioEntity> batchSave(List<OpsEnvioRequestDTO> dtos) {
        Map<String, String> continentByIata = new HashMap<>();
        for (Aeropuerto a : opsReferenceData.getAeropuertos()) {
            continentByIata.put(a.getCodigoIATA(), a.getContinente());
        }

        List<EnvioEntity> saved = new ArrayList<>();
        for (OpsEnvioRequestDTO dto : dtos) {
            boolean hasId = dto.getIdPedido() != null && !dto.getIdPedido().isBlank();

            LocalDateTime fechaUtc;
            try {
                OffsetDateTime offsetDt = OffsetDateTime.parse(dto.getFechaHoraIngreso());
                // Store as UTC (consistent with addEnvio; downstream query/toDomain assume UTC)
                fechaUtc = offsetDt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (Exception ex) {
                // Already naive local time (from file preview) — treat as UTC
                fechaUtc = LocalDateTime.parse(dto.getFechaHoraIngreso());
            }

            String continenteOrigen = continentByIata.get(dto.getIataOrigen());
            String continenteDestino = continentByIata.get(dto.getIataDestino());
            int sla = (continenteOrigen != null && continenteOrigen.equals(continenteDestino)) ? 1 : 2;

            String idPedido;
            if (hasId) {
                idPedido = dto.getIdPedido();
            } else {
                // Sequential ID per origin airport: IATA-000000001
                long count = opsEnvioRepository.countByIataOrigen(dto.getIataOrigen().toUpperCase());
                idPedido = dto.getIataOrigen().toUpperCase() + "-" + String.format("%09d", count + 1);
            }

            EnvioEntity entity = EnvioEntity.builder()
                    .idPedido(idPedido)
                    .codigoAerolinea(validateCodigoAerolinea(dto.getCodigoAerolinea()))
                    .iataOrigen(dto.getIataOrigen())
                    .iataDestino(dto.getIataDestino())
                    .cantidadMaletas(dto.getCantidadMaletas())
                    .fechaHoraIngreso(fechaUtc)
                    .sla(sla)
                    .estado("PENDIENTE")
                    .build();

            saved.add(opsEnvioRepository.save(entity));
        }
        return saved;
    }

    // -------------------------------------------------------------------------
    // 8. getAirportInventory (ops mode)
    // -------------------------------------------------------------------------

    @Transactional(value = "opsTransactionManager", readOnly = true)
    public AirportInventoryDTO getAirportInventory(String iata) {
        String upper = iata.toUpperCase();

        // En almacén: PENDIENTE (sin ruta) o PLANIFICADO (con ruta, esperando salida) envíos
        // whose origin is this airport. Mark each with whether it has a route plan.
        List<EnvioSummaryDTO> enAlmacen = opsEnvioRepository
                .findAllByEstadoInAndIataOrigen(List.of("PENDIENTE", "PLANIFICADO"), upper)
                .stream()
                .map(e -> {
                    List<PlanDeViaje> planList = planesPorEnvio.get(e.getIdPedido());
                    boolean hasPlan = planList != null && !planList.isEmpty();
                    List<String> ruta = null;
                    if (hasPlan) {
                        PlanDeViaje plan = planList.get(0);
                        if (plan.getEscalas() != null && !plan.getEscalas().isEmpty()) {
                            ruta = new ArrayList<>();
                            ruta.add(e.getIataOrigen());
                            List<Escala> ordenadas = plan.getEscalas().stream()
                                    .sorted(Comparator.comparingInt(Escala::getOrden))
                                    .toList();
                            for (Escala esc : ordenadas) {
                                ruta.add(esc.getCodigoAeropuerto());
                            }
                        }
                    }
                    return EnvioSummaryDTO.builder()
                            .idEnvio(e.getIdPedido())
                            .aeropuertoOrigen(e.getIataOrigen())
                            .aeropuertoDestino(e.getIataDestino())
                            .cantidadMaletas(e.getCantidadMaletas())
                            .estado(e.getEstado())
                            .sla(e.getSla())
                            .planificado(hasPlan)
                            .rutaCompleta(ruta)
                            .build();
                })
                .sorted(Comparator.comparing(EnvioSummaryDTO::getIdEnvio))
                .collect(Collectors.toList());

        // Build a lookup of idPedido -> entity for plan processing
        Map<String, EnvioEntity> entityById = opsEnvioRepository.findAll().stream()
                .collect(Collectors.toMap(EnvioEntity::getIdPedido, e -> e, (a, b) -> a));

        List<EnvioSummaryDTO> entrando = new ArrayList<>();
        List<EnvioSummaryDTO> saliendo = new ArrayList<>();

        for (List<PlanDeViaje> planList : planesPorEnvio.values()) {
            for (PlanDeViaje plan : planList) {
            EnvioEntity ent = entityById.get(plan.getIdEnvio());
            if (ent == null || plan.getEscalas() == null || plan.getEscalas().isEmpty()) continue;

            // Escalas are ordered; each escala.codigoAeropuerto is the DESTINATION of a leg.
            // The leg origin is envío.iataOrigen for the first leg, and the previous
            // escala's codigoAeropuerto for later legs. So we reconstruct leg origins here.
            List<Escala> escalas = new ArrayList<>(plan.getEscalas());
            escalas.sort(Comparator.comparingInt(Escala::getOrden));

            String prevAeropuerto = ent.getIataOrigen();
            for (Escala esc : escalas) {
                String legOrigen = prevAeropuerto;
                String legDestino = esc.getCodigoAeropuerto();

                // Saliendo: this airport is the origin of this leg
                if (upper.equalsIgnoreCase(legOrigen) && esc.getHoraSalidaEst() != null) {
                    saliendo.add(summaryFromPlan(plan, ent, esc.getCodigoVuelo(),
                            esc.getHoraSalidaEst()));
                }
                // Entrando: this airport is the destination of this leg
                if (upper.equalsIgnoreCase(legDestino) && esc.getHoraLlegadaEst() != null) {
                    entrando.add(summaryFromPlan(plan, ent, esc.getCodigoVuelo(),
                            esc.getHoraLlegadaEst()));
                }

                prevAeropuerto = legDestino;
            }
            } // end inner plan loop
        }

        entrando.sort(Comparator.comparing(EnvioSummaryDTO::getHora, Comparator.nullsLast(Comparator.naturalOrder())));
        saliendo.sort(Comparator.comparing(EnvioSummaryDTO::getHora, Comparator.nullsLast(Comparator.naturalOrder())));

        // Sin ruta: PENDIENTE envíos with origin here that have no plan after planificar
        List<EnvioSummaryDTO> sinRuta = enAlmacen.stream()
                .filter(e -> Boolean.FALSE.equals(e.getPlanificado()))
                .collect(Collectors.toList());

        return AirportInventoryDTO.builder()
                .iata(upper)
                .enAlmacen(enAlmacen)
                .planificadosEntrando(entrando)
                .planificadosSaliendo(saliendo)
                .sinRuta(sinRuta)
                .build();
    }

    private EnvioSummaryDTO summaryFromPlan(PlanDeViaje plan, EnvioEntity ent,
            String codigoVuelo, LocalDateTime hora) {
        int qty = plan.getCantidadMaletas() > 0 ? plan.getCantidadMaletas() : ent.getCantidadMaletas();
        return EnvioSummaryDTO.builder()
                .idEnvio(plan.getIdEnvio())
                .aeropuertoOrigen(ent.getIataOrigen())
                .aeropuertoDestino(ent.getIataDestino())
                .cantidadMaletas(qty)
                .estado(ent.getEstado())
                .sla(ent.getSla())
                .planificado(true)
                .codigoVuelo(codigoVuelo)
                .hora(hora.toLocalTime().toString().substring(0, 5))
                .build();
    }

    // -------------------------------------------------------------------------
    // 9. procesarSalidas — called by OpsScheduler every ~30s.
    //    Transitions PLANIFICADO envíos to EN_TRANSITO when horaSalidaEst <= now.
    // -------------------------------------------------------------------------

    @Transactional("opsTransactionManager")
    public void procesarSalidas() {
        if (planesPorEnvio.isEmpty()) return;
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        for (EnvioEntity envio : opsEnvioRepository.findAllByEstado("PLANIFICADO")) {
            List<PlanDeViaje> plans = planesPorEnvio.get(envio.getIdPedido());
            if (plans == null || plans.isEmpty()) continue;
            PlanDeViaje plan = plans.get(0);
            if (plan.getEscalas() == null || plan.getEscalas().isEmpty()) continue;

            int orden = ordenActualByEnvio.getOrDefault(envio.getIdPedido(), 1);

            plan.getEscalas().stream()
                    .filter(e -> e.getOrden() == orden)
                    .findFirst()
                    .ifPresent(escala -> {
                        // horaSalidaEst is UTC (built by the planner from UTC inputs); compare directly.
                        if (!escala.getHoraSalidaEst().isAfter(nowUtc)) {
                            // Must match EstadoEnvio.EN_TRANSITO — the frontend (SidePanel,
                            // EnviosScreen) and the enum itself key off this exact string to
                            // color/filter/route in-transit envíos.
                            envio.setEstado("EN_TRANSITO");
                            opsEnvioRepository.save(envio);
                            log.info("Salida: {} en vuelo {} (escala {})",
                                    envio.getIdPedido(), escala.getCodigoVuelo(), orden);
                        }
                    });
        }
    }

    // -------------------------------------------------------------------------
    // 10. procesarLlegadas — called by OpsScheduler every ~30s (after salidas).
    //     Transitions EN_TRANSITO envíos: intermediate stop → PLANIFICADO at new iataOrigen,
    //     or final destination → ENTREGADO.
    // -------------------------------------------------------------------------

    @Transactional("opsTransactionManager")
    public void procesarLlegadas() {
        if (planesPorEnvio.isEmpty()) return;
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        for (EnvioEntity envio : opsEnvioRepository.findAllByEstado("EN_TRANSITO")) {
            List<PlanDeViaje> plans = planesPorEnvio.get(envio.getIdPedido());
            if (plans == null || plans.isEmpty()) continue;
            PlanDeViaje plan = plans.get(0);
            if (plan.getEscalas() == null || plan.getEscalas().isEmpty()) continue;

            int orden = ordenActualByEnvio.getOrDefault(envio.getIdPedido(), 1);
            List<Escala> escalas = plan.getEscalas().stream()
                    .sorted(Comparator.comparingInt(Escala::getOrden))
                    .toList();

            escalas.stream()
                    .filter(e -> e.getOrden() == orden)
                    .findFirst()
                    .ifPresent(escala -> {
                        // horaSalidaEst/horaLlegadaEst are UTC; compare directly.
                        if (!escala.getHoraLlegadaEst().isAfter(nowUtc)) {
                            boolean hayMasEscalas = escalas.stream()
                                    .anyMatch(e -> e.getOrden() == orden + 1);
                            if (hayMasEscalas) {
                                // Intermediate stop: bag still has a plan, waits at this airport for next leg
                                envio.setEstado("PLANIFICADO");
                                envio.setIataOrigen(escala.getCodigoAeropuerto());
                                ordenActualByEnvio.put(envio.getIdPedido(), orden + 1);
                                log.info("Llegada intermedia: {} en {} (próxima escala {})",
                                        envio.getIdPedido(), escala.getCodigoAeropuerto(), orden + 1);
                            } else {
                                envio.setEstado("ENTREGADO");
                                envio.setFechaEntrega(nowUtc);
                                log.info("Entregado: {}", envio.getIdPedido());
                            }
                            opsEnvioRepository.save(envio);
                        }
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Helper: buildPlanResumen
    // -------------------------------------------------------------------------

    private String buildPlanResumen(String origen, String destino, PlanDeViaje plan) {
        if (plan == null || plan.getEscalas() == null || plan.getEscalas().isEmpty()) {
            return null;
        }
        List<String> hubs = plan.getEscalas().stream()
                .map(Escala::getCodigoAeropuerto)
                .filter(code -> !code.equals(destino))
                .distinct()
                .toList();
        if (hubs.isEmpty()) {
            return origen + " → " + destino;
        }
        return origen + " → " + String.join(" → ", hubs) + " → " + destino;
    }

    // -------------------------------------------------------------------------
    // Helper: toDomain
    // -------------------------------------------------------------------------

    private Envio toDomain(EnvioEntity e) {
        // fechaHoraIngreso is stored as UTC, matching Vuelo.horaSalida/horaLlegada
        // (also UTC, see getLiveState). The planner compares them directly, so no
        // huso conversion here — converting to local would desync it from flight times.
        return Envio.builder()
                .idEnvio(e.getIdPedido())
                .aeropuertoOrigen(e.getIataOrigen())
                .aeropuertoDestino(e.getIataDestino())
                .cantidadMaletas(e.getCantidadMaletas())
                .fechaHoraIngreso(e.getFechaHoraIngreso())
                .sla(e.getSla())
                .estado(EstadoEnvio.valueOf(e.getEstado()))
                .build();
    }
}
