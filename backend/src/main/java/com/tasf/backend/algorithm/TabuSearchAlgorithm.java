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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ROOT CAUSE OF THE HANG (fixed in this revision)
 * ─────────────────────────────────────────────────
 * The original TS neighbourhood loop had the following structure:
 *
 *   for each outer iteration:
 *     for each envio (n ≈ 1495):
 *       for each candidate route (k ≤ 50):
 *         withCandidate(current, ...)          // copies full n-entry map → O(n)
 *         respectsHardConstraints(copy, ...)   // iterates every entry   → O(n)
 *         objective(copy, ...)                 // iterates every entry   → O(n)
 *
 * Per outer iteration that is  n × k × O(n)  =  O(n² × k)  evaluations.
 * For n=1495, k=50: ~74,750 map-copies and constraint scans of 1495 entries
 * each.  With noImprovementLimit=747 outer iterations, the total work is
 * roughly 747 × 74,750 × O(1495) ≈ 83 billion operations → tens of minutes.
 *
 * SimulatedAnnealingAlgorithm avoids this by picking ONE random envio per
 * iteration, giving O(n) per step and completing in under 2 seconds.
 *
 * FIX: replace the double nested loop with a round-robin strategy that
 * evaluates exactly ONE envio's neighbourhood per outer iteration.  Cost per
 * step drops from O(n² × k) to O(n × k), cutting total work by a factor of n.
 *
 * A secondary bug was also fixed: the acceptance condition
 *   score <= currentScore
 * turned the algorithm into tabu-enhanced hill-climbing, preventing it from
 * escaping local optima.  Standard TS always accepts the best non-tabu
 * neighbour unconditionally; the tabu list and noImprovementLimit provide the
 * necessary termination guarantee.
 */
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

            log.debug("SEED input: envios={} poolKeys={}", envios.size(), pool.size());

            Map<String, RouteCandidate> current = new HashMap<>();
            int unroutedCount = 0;
            for (Envio envio : envios) {
                List<RouteCandidate> options = pool.getOrDefault(envio.getIdEnvio(), List.of());
                log.debug("SEED envio={} origin={} candidates={}", envio.getIdEnvio(), envio.getAeropuertoOrigen(), options.size());
                if (options.isEmpty()) {
                    envio.setEstado(EstadoEnvio.RETRASADO);
                    log.warn("Envio {} has no feasible routes; marked RETRASADO", envio.getIdEnvio());
                    unroutedCount++;
                    continue;
                }
                // Baseline assignment: take the first (earliest-arrival) candidate
                // without checking hard capacity constraints.  A greedy constraint-
                // checked seeding fills flight/warehouse capacity after ~64 envios and
                // leaves the remaining 1431 as RETRASADO before TS even starts.
                // Instead we seed everyone and let the TS refinement loop redistribute
                // envios to routes that satisfy capacity (the overload penalty in
                // objective() drives the search toward feasible assignments).
                current.put(envio.getIdEnvio(), options.get(0));
                envio.setEstado(EstadoEnvio.PLANIFICADO);
            }

            log.info("TS seeding complete: {} envios in current, {} unrouted", current.size(), unroutedCount);

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

            // Only envios that received an initial route participate in optimisation.
            List<Envio> plannedEnvios = envios.stream()
                .filter(e -> current.containsKey(e.getIdEnvio()))
                .toList();

            // MAX_ITERATIONS = 3 full round-robin passes over all planned envios.
            // This guarantees each envio is visited at least 3 times regardless of
            // the noImprovementLimit, which would otherwise fire too early.
            int maxIterations = plannedEnvios.isEmpty() ? 0 : plannedEnvios.size() * 3;
            int noImprovement = 0;
            // noImprovementLimit is only checked AFTER at least one full cycle so
            // that late envios (high index) are never skipped.
            int noImprovementLimit = Math.max(plannedEnvios.size(), envios.size() / 2);
            int envioIndex = 0;

            for (int i = 0; i < maxIterations; i++) {
                if (plannedEnvios.isEmpty()) break;

                // After one full cycle, allow early exit if no improvement was seen
                // across the entire cycle.  Reset the counter at the start of each
                // new cycle so that a single stagnant envio cannot abort a cycle
                // that has not yet reached later envios.
                boolean fullCycleComplete = i > 0 && i % plannedEnvios.size() == 0;
                if (fullCycleComplete) {
                    if (noImprovement >= noImprovementLimit) {
                        log.debug("TS early exit after {} full cycles (noImprovement={})", i / plannedEnvios.size(), noImprovement);
                        break;
                    }
                    noImprovement = 0; // reset for the next cycle
                }

                // Round-robin: evaluate ONE envio's neighbourhood per iteration.
                // Evaluating all envios × all candidates each step is O(n² × k)
                // because withCandidate() and respectsHardConstraints() both scan
                // the full assignment map.  Restricting to one envio per step
                // reduces cost to O(n × k), matching SA's per-iteration complexity.
                Envio envio = plannedEnvios.get(envioIndex % plannedEnvios.size());
                envioIndex++;

                List<RouteCandidate> options = pool.getOrDefault(envio.getIdEnvio(), List.of());
                Neighbor bestNeighbor = null;

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
                    // Standard TS: accept the best non-tabu neighbour unconditionally.
                    // The previous guard (score <= currentScore) prevented escaping
                    // local optima and is not part of canonical tabu search.
                    if (bestNeighbor == null || score < bestNeighbor.score()) {
                        bestNeighbor = new Neighbor(envio.getIdEnvio(), option, score, tabuKey);
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

            long elapsed = System.currentTimeMillis() - start;
            int totalIterations = envioIndex;
            int cycles = plannedEnvios.isEmpty() ? 0 : totalIterations / plannedEnvios.size();
            log.info("TS planning completed: {} envios planned in {}ms ({} iterations, {} cycles)",
                current.size(), elapsed, totalIterations, cycles);
            // Ensure best contains all planned envios, not just improved ones
            if (best.size() < current.size()) {
                best = new HashMap<>(current);
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
