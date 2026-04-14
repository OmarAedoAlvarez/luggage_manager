package com.tasf.backend.algorithm;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.Vuelo;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

abstract class RoutePlannerSupport {

    protected Map<String, List<RouteCandidate>> buildCandidatePool(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params,
        MutableCounter routeCounter
    ) {
        Map<String, Aeropuerto> airportByCode = aeropuertos.stream()
            .collect(Collectors.toMap(Aeropuerto::getCodigoIATA, airport -> airport, (a, b) -> a));
        Map<String, List<Vuelo>> flightsByOrigin = vuelos.stream()
            .filter(vuelo -> !vuelo.isCancelado())
            .collect(Collectors.groupingBy(Vuelo::getOrigen));

        Map<String, List<RouteCandidate>> pool = new HashMap<>();
        for (Envio envio : envios) {
            enforceSlaFromContinent(envio, airportByCode);
            List<RouteCandidate> routes = generateRoutes(envio, flightsByOrigin, airportByCode, params);
            routeCounter.increment(routes.size());
            pool.put(envio.getIdEnvio(), routes);
        }

        return pool;
    }

    protected void enforceSlaFromContinent(Envio envio, Map<String, Aeropuerto> airportByCode) {
        String originContinent = Optional.ofNullable(airportByCode.get(envio.getAeropuertoOrigen()))
            .map(Aeropuerto::getContinente)
            .orElse("unknown");
        String destinationContinent = Optional.ofNullable(airportByCode.get(envio.getAeropuertoDestino()))
            .map(Aeropuerto::getContinente)
            .orElse("unknown");

        int expectedSla = Objects.equals(originContinent, destinationContinent) ? 1 : 2;
        envio.setSla(expectedSla);
        if (envio.getEstado() == null) {
            envio.setEstado(EstadoEnvio.PENDIENTE);
        }
    }

    protected List<RouteCandidate> generateRoutes(
        Envio envio,
        Map<String, List<Vuelo>> flightsByOrigin,
        Map<String, Aeropuerto> airportByCode,
        ParametrosSimulacion params
    ) {
        List<RouteCandidate> routes = new ArrayList<>();
        LocalDateTime deadline = envio.getFechaHoraIngreso().plusDays(envio.getSla());

        List<Vuelo> directFlights = flightsByOrigin.getOrDefault(envio.getAeropuertoOrigen(), List.of())
            .stream()
            .filter(f -> f.getDestino().equals(envio.getAeropuertoDestino()))
            .toList();

        routes.addAll(directFlights.stream()
            .map(flight -> buildDirectCandidate(envio, flight))
            .flatMap(Optional::stream)
            .filter(route -> isWithinSla(route, deadline, params))
            .toList());

        List<Vuelo> firstLegs = flightsByOrigin.getOrDefault(envio.getAeropuertoOrigen(), List.of())
            .stream()
            .filter(flight -> !flight.getDestino().equals(envio.getAeropuertoDestino()))
            .toList();

        routes.addAll(firstLegs.stream()
            .flatMap(first -> flightsByOrigin.getOrDefault(first.getDestino(), List.of()).stream()
                .filter(second -> second.getDestino().equals(envio.getAeropuertoDestino()))
                .map(second -> buildOneStopCandidate(envio, first, second, params)))
            .flatMap(Optional::stream)
            .filter(route -> isWithinSla(route, deadline, params))
            .toList());

        return routes.stream()
            .sorted(Comparator.comparing(RouteCandidate::getFinalArrival))
            .limit(50)
            .toList();
    }

    protected Optional<RouteCandidate> buildDirectCandidate(Envio envio, Vuelo flight) {
        LocalDateTime departure = nextDateTimeForFlight(envio.getFechaHoraIngreso(), flight.getHoraSalida());
        LocalDateTime arrival = arrivalDateTime(departure, flight.getHoraSalida(), flight.getHoraLlegada());
        return Optional.of(new RouteCandidate(List.of(new RouteCandidate.Leg(flight, departure, arrival))));
    }

    protected Optional<RouteCandidate> buildOneStopCandidate(Envio envio, Vuelo first, Vuelo second, ParametrosSimulacion params) {
        LocalDateTime firstDeparture = nextDateTimeForFlight(envio.getFechaHoraIngreso(), first.getHoraSalida());
        LocalDateTime firstArrival = arrivalDateTime(firstDeparture, first.getHoraSalida(), first.getHoraLlegada());
        LocalDateTime secondEarliest = firstArrival.plusMinutes(params.getMinutosEscalaMinima());
        LocalDateTime secondDeparture = nextDateTimeForFlight(secondEarliest, second.getHoraSalida());

        if (Duration.between(firstArrival, secondDeparture).toMinutes() < params.getMinutosEscalaMinima()) {
            return Optional.empty();
        }

        LocalDateTime secondArrival = arrivalDateTime(secondDeparture, second.getHoraSalida(), second.getHoraLlegada());
        return Optional.of(new RouteCandidate(List.of(
            new RouteCandidate.Leg(first, firstDeparture, firstArrival),
            new RouteCandidate.Leg(second, secondDeparture, secondArrival)
        )));
    }

    protected boolean isWithinSla(RouteCandidate candidate, LocalDateTime deadline, ParametrosSimulacion params) {
        return !candidate.getFinalArrival().plusMinutes(params.getMinutosRecogidaDestino()).isAfter(deadline);
    }

    protected double objective(
        Map<String, RouteCandidate> assignment,
        Map<String, Envio> envioById,
        ParametrosSimulacion params
    ) {
        long slaViolations = assignment.entrySet().stream()
            .filter(entry -> {
                Envio envio = envioById.get(entry.getKey());
                LocalDateTime deadline = envio.getFechaHoraIngreso().plusDays(envio.getSla());
                return entry.getValue().getFinalArrival().plusMinutes(params.getMinutosRecogidaDestino()).isAfter(deadline);
            })
            .count();

        Map<String, Integer> warehouseLoads = new HashMap<>();
        assignment.forEach((id, route) -> {
            int quantity = envioById.get(id).getCantidadMaletas();
            route.getIntermediateAirports().forEach(hub ->
                warehouseLoads.merge(hub, quantity, Integer::sum)
            );
        });

        double maxWarehouseAllowed = params.getCapacidadAlmacen() * 0.9d;
        double overload = warehouseLoads.values().stream()
            .mapToDouble(load -> Math.max(0, load - maxWarehouseAllowed))
            .sum();

        return slaViolations + (overload * 10.0d);
    }

    protected boolean respectsHardConstraints(
        Map<String, RouteCandidate> assignment,
        Map<String, Envio> envioById,
        ParametrosSimulacion params
    ) {
        Map<String, Integer> flightLoads = new HashMap<>();
        Map<String, Integer> warehouseLoads = new HashMap<>();

        for (Map.Entry<String, RouteCandidate> entry : assignment.entrySet()) {
            Envio envio = envioById.get(entry.getKey());
            int quantity = envio.getCantidadMaletas();

            for (RouteCandidate.Leg leg : entry.getValue().getLegs()) {
                int maxFlightCapacity = Math.min(leg.flight().getCapacidadTotal(), params.getCapacidadVuelo());
                int projectedLoad = flightLoads.merge(leg.flight().getCodigoVuelo(), quantity, Integer::sum);
                if (projectedLoad > maxFlightCapacity) {
                    return false;
                }
            }

            for (String hub : entry.getValue().getIntermediateAirports()) {
                int projectedHubLoad = warehouseLoads.merge(hub, quantity, Integer::sum);
                if (projectedHubLoad > (int) Math.floor(params.getCapacidadAlmacen() * 0.9d)) {
                    return false;
                }
            }
        }
        return true;
    }

    protected LocalDateTime nextDateTimeForFlight(LocalDateTime earliest, LocalTime scheduleTime) {
        LocalDateTime candidate = earliest.toLocalDate().atTime(scheduleTime);
        while (candidate.isBefore(earliest)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    protected LocalDateTime arrivalDateTime(LocalDateTime departure, LocalTime departureTime, LocalTime arrivalTime) {
        LocalDateTime arrival = departure.toLocalDate().atTime(arrivalTime);
        if (arrival.isBefore(departure) || arrivalTime.equals(departureTime)) {
            arrival = arrival.plusDays(1);
        }
        return arrival;
    }

    protected record MutableCounter(int[] value) {
        MutableCounter() {
            this(new int[] {0});
        }

        void increment(int amount) {
            value[0] += amount;
        }

        int get() {
            return value[0];
        }
    }
}
