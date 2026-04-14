package com.tasf.backend.algorithm;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.Vuelo;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("TABU_SEARCH")
public class TabuSearchAlgorithm extends RoutePlannerSupport implements MetaheuristicAlgorithm {
    private static final Logger log = LoggerFactory.getLogger(TabuSearchAlgorithm.class);
    private MetricaAlgoritmo ultimaMetrica;

    @Override
    public List<PlanDeViaje> planificar(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params
    ) {
        long start = System.currentTimeMillis();
        MutableCounter routeCounter = new MutableCounter();
        this.ultimaMetrica = null;

        try {
            Map<String, List<RouteCandidate>> pool = buildCandidatePool(envios, vuelos, aeropuertos, params, routeCounter);
            Map<String, Envio> envioById = envios.stream().collect(HashMap::new, (m, e) -> m.put(e.getIdEnvio(), e), Map::putAll);

            Map<String, RouteCandidate> current = new HashMap<>();
            for (Envio envio : envios) {
                List<RouteCandidate> options = pool.getOrDefault(envio.getIdEnvio(), List.of());
                if (options.isEmpty()) {
                    envio.setEstado(EstadoEnvio.RETRASADO);
                    log.warn("Envio {} has no feasible routes; marked RETRASADO", envio.getIdEnvio());
                    continue;
                }
                Optional<RouteCandidate> feasible = options.stream()
                    .filter(option -> respectsHardConstraints(withCandidate(current, envio.getIdEnvio(), option), envioById, params))
                    .findFirst();
                if (feasible.isPresent()) {
                    current.put(envio.getIdEnvio(), feasible.get());
                    envio.setEstado(EstadoEnvio.PLANIFICADO);
                } else {
                    envio.setEstado(EstadoEnvio.RETRASADO);
                    log.warn("No feasible initial route for envio {}", envio.getIdEnvio());
                }
            }

            if (current.isEmpty()) {
                saveMetric(start, routeCounter.get());
                return List.of();
            }

            Map<String, RouteCandidate> best = new HashMap<>(current);
            double currentScore = objective(current, envioById, params);
            double bestScore = currentScore;

            int tabuSize = Math.max(1, Math.min(20, envios.size() / 2));
            Deque<String> tabuQueue = new ArrayDeque<>();
            Set<String> tabuSet = new HashSet<>();

            int maxIterations = Math.min(10000, 100 * Math.max(1, envios.size()));
            int noImprovement = 0;
            int noImprovementLimit = Math.max(100, envios.size() / 2);

            for (int i = 0; i < maxIterations && noImprovement < noImprovementLimit; i++) {
                Neighbor bestNeighbor = null;

                for (Envio envio : envios) {
                    List<RouteCandidate> options = pool.getOrDefault(envio.getIdEnvio(), List.of());
                    for (RouteCandidate option : options) {
                        routeCounter.increment(1);
                        RouteCandidate currentOption = current.get(envio.getIdEnvio());
                        if (currentOption != null && option.getSignature().equals(currentOption.getSignature())) {
                            continue;
                        }

                        String tabuKey = envio.getIdEnvio() + "::" + option.getPrimaryFlightCode();
                        if (tabuSet.contains(tabuKey)) {
                            continue;
                        }

                        Map<String, RouteCandidate> candidate = withCandidate(current, envio.getIdEnvio(), option);
                        if (!respectsHardConstraints(candidate, envioById, params)) {
                            continue;
                        }

                        double score = objective(candidate, envioById, params);
                        if (score <= currentScore && (bestNeighbor == null || score < bestNeighbor.score())) {
                            bestNeighbor = new Neighbor(envio.getIdEnvio(), option, score, tabuKey);
                        }
                    }
                }

                if (bestNeighbor == null) {
                    noImprovement++;
                    continue;
                }

                current.put(bestNeighbor.envioId(), bestNeighbor.route());
                currentScore = bestNeighbor.score();
                tabuQueue.addLast(bestNeighbor.tabuKey());
                tabuSet.add(bestNeighbor.tabuKey());
                while (tabuQueue.size() > tabuSize) {
                    tabuSet.remove(tabuQueue.removeFirst());
                }

                if (currentScore < bestScore) {
                    bestScore = currentScore;
                    best = new HashMap<>(current);
                    noImprovement = 0;
                } else {
                    noImprovement++;
                }
            }

            saveMetric(start, routeCounter.get());
            return toPlans(best, envioById, params, getNombre());
        } catch (RuntimeException ex) {
            log.error("Tabu search failed; returning best known partial solution", ex);
            // Mark any envio that was never assigned a route as RETRASADO so it doesn't stay PLANIFICADO
            envios.stream()
                .filter(e -> e.getEstado() == EstadoEnvio.PLANIFICADO || e.getEstado() == EstadoEnvio.PENDIENTE)
                .forEach(e -> e.setEstado(EstadoEnvio.RETRASADO));
            saveMetric(start, routeCounter.get());
            return List.of();
        }
    }

    @Override
    public String getNombre() {
        return "TABU_SEARCH";
    }

    @Override
    public MetricaAlgoritmo getUltimaMetrica() {
        return ultimaMetrica;
    }

    private void saveMetric(long start, int routesEvaluated) {
        this.ultimaMetrica = MetricaAlgoritmo.builder()
            .idMetrica("MET-" + getNombre() + "-" + System.nanoTime())
            .algoritmoUsado(getNombre())
            .tiempoEjecucionMs(Math.max(1, System.currentTimeMillis() - start))
            .rutasEvaluadas(Math.max(0, routesEvaluated))
            .fechaEjecucion(LocalDateTime.now())
            .build();
    }

    private Map<String, RouteCandidate> withCandidate(Map<String, RouteCandidate> current, String envioId, RouteCandidate candidate) {
        Map<String, RouteCandidate> copy = new HashMap<>(current);
        copy.put(envioId, candidate);
        return copy;
    }

    private List<PlanDeViaje> toPlans(
        Map<String, RouteCandidate> selectedRoutes,
        Map<String, Envio> envioById,
        ParametrosSimulacion params,
        String algorithm
    ) {
        return selectedRoutes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().toPlan(envioById.get(entry.getKey()), algorithm, 1, params))
            .toList();
    }

    private record Neighbor(String envioId, RouteCandidate route, double score, String tabuKey) {
    }
}
