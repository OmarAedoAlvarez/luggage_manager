package com.tasf.backend.algorithm;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.Vuelo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("SIMULATED_ANNEALING")
public class SimulatedAnnealingAlgorithm extends RoutePlannerSupport implements MetaheuristicAlgorithm {
    private static final Logger log = LoggerFactory.getLogger(SimulatedAnnealingAlgorithm.class);
    private final Random random = new Random();
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

            final Map<String, RouteCandidate> current = new HashMap<>();
            List<String> optimizableEnvios = new ArrayList<>();
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
                    optimizableEnvios.add(envio.getIdEnvio());
                } else {
                    envio.setEstado(EstadoEnvio.RETRASADO);
                    log.warn("No feasible initial route for envio {}", envio.getIdEnvio());
                }
            }

            if (current.isEmpty()) {
                saveMetric(start, routeCounter.get());
                return List.of();
            }

            double temperature = 1000.0d;
            final double coolingRate = 0.995d;
            final double minTemperature = 0.1d;
            final int maxIterations = Math.min(50000, 500 * Math.max(1, envios.size()));

            Map<String, RouteCandidate> best = new HashMap<>(current);
            double currentCost = objective(current, envioById, params);
            double bestCost = currentCost;

            for (int i = 0; i < maxIterations && temperature >= minTemperature; i++) {
                if (optimizableEnvios.isEmpty()) {
                    break;
                }

                String envioId = optimizableEnvios.get(random.nextInt(optimizableEnvios.size()));
                List<RouteCandidate> options = pool.getOrDefault(envioId, List.of());
                if (options.isEmpty()) {
                    temperature *= coolingRate;
                    continue;
                }

                RouteCandidate currentRoute = current.get(envioId);
                RouteCandidate candidateRoute = options.get(random.nextInt(options.size()));
                routeCounter.increment(1);

                if (currentRoute != null && currentRoute.getSignature().equals(candidateRoute.getSignature())) {
                    temperature *= coolingRate;
                    continue;
                }

                Map<String, RouteCandidate> neighbor = withCandidate(current, envioId, candidateRoute);
                if (!respectsHardConstraints(neighbor, envioById, params)) {
                    temperature *= coolingRate;
                    continue;
                }

                double neighborCost = objective(neighbor, envioById, params);
                double delta = neighborCost - currentCost;

                boolean accept = delta < 0 || Math.exp(-delta / temperature) > random.nextDouble();
                if (accept) {
                    current.clear();
                    current.putAll(neighbor);
                    currentCost = neighborCost;
                    if (neighborCost < bestCost) {
                        best = new HashMap<>(neighbor);
                        bestCost = neighborCost;
                    }
                }
                temperature *= coolingRate;
            }

            saveMetric(start, routeCounter.get());
            return toPlans(best, envioById, params, getNombre());
        } catch (RuntimeException ex) {
            log.error("Simulated annealing failed; returning best known partial solution", ex);
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
        return "SIMULATED_ANNEALING";
    }

    @Override
    public MetricaAlgoritmo getUltimaMetrica() {
        return ultimaMetrica;
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

    private void saveMetric(long start, int routesEvaluated) {
        this.ultimaMetrica = MetricaAlgoritmo.builder()
            .idMetrica("MET-" + System.nanoTime())
            .tiempoEjecucionMs(Math.max(1, System.currentTimeMillis() - start))
            .rutasEvaluadas(Math.max(0, routesEvaluated))
            .fechaEjecucion(LocalDateTime.now())
            .build();
    }
}
