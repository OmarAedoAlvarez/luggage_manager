package com.tasf.backend.service;

import com.tasf.backend.algorithm.MetaheuristicAlgorithm;
import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.PlanningResult;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.Vuelo;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {
    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    private static final String SA = "SIMULATED_ANNEALING";
    private static final String TS = "TABU_SEARCH";
    private static final int COMPLEXITY_THRESHOLD_FOR_TABU = 25;
    private final Map<String, MetaheuristicAlgorithm> algorithms;

    public PlanningService(List<MetaheuristicAlgorithm> algorithms) {
        this.algorithms = algorithms.stream()
            .collect(Collectors.toMap(MetaheuristicAlgorithm::getNombre, Function.identity(), (a, b) -> a));
    }

    public PlanningResult planificar(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params
    ) {
        return planificarInterno(envios, vuelos, aeropuertos, params, false);
    }

    public PlanningResult planificarConIncidencia(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params
    ) {
        return planificarInterno(envios, vuelos, aeropuertos, params, true);
    }

    private PlanningResult planificarInterno(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params,
        boolean conIncidencia
    ) {
        MetaheuristicAlgorithm selected = selectAlgorithm(params, conIncidencia);
        log.info("Planning {} envios with {} (incidencia={})", envios.size(), selected.getNombre(), conIncidencia);
        List<PlanDeViaje> planes = selected.planificar(envios, vuelos, aeropuertos, params);
        Set<String> enviosConPlan = planes.stream().map(PlanDeViaje::getIdEnvio).collect(Collectors.toSet());
        List<String> enviosSinRuta = envios.stream()
            .map(Envio::getIdEnvio)
            .filter(id -> !enviosConPlan.contains(id))
            .toList();

        return PlanningResult.builder()
            .planes(planes)
            .metrica(selected.getUltimaMetrica())
            .enviosSinRuta(enviosSinRuta)
            .build();
    }

    private MetaheuristicAlgorithm selectAlgorithm(ParametrosSimulacion params, boolean conIncidencia) {
        // For normal planning, honor the requested algorithm so the same dataset can
        // be executed separately with SA or TS. Incidence replanning keeps TS.
        String requested = conIncidencia ? TS : normalizeAlgorithm(params != null ? params.getAlgoritmo() : null);
        String selectedName = requested != null ? requested : SA;
        MetaheuristicAlgorithm selected = algorithms.get(selectedName);
        if (selected == null) {
            throw new IllegalStateException("Algorithm not configured: " + selectedName);
        }
        return selected;
    }

    private String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) {
            return null;
        }
        String normalized = algorithm.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return null;
        }
        if ("SA".equals(normalized)) {
            return SA;
        }
        if ("TS".equals(normalized)) {
            return TS;
        }
        return normalized;
    }
}
