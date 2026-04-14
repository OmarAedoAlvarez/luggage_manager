package com.tasf.backend.service;

import com.tasf.backend.algorithm.SimulatedAnnealingAlgorithm;
import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.PlanningResult;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.Vuelo;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {
    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    private final SimulatedAnnealingAlgorithm algorithm;

    public PlanningService(SimulatedAnnealingAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public PlanningResult planificar(
        List<Envio> envios,
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        ParametrosSimulacion params
    ) {
        log.info("Planning {} envios with SIMULATED_ANNEALING", envios.size());
        List<PlanDeViaje> planes = algorithm.planificar(envios, vuelos, aeropuertos, params);
        Set<String> enviosConPlan = planes.stream().map(PlanDeViaje::getIdEnvio).collect(Collectors.toSet());
        List<String> enviosSinRuta = envios.stream()
            .map(Envio::getIdEnvio)
            .filter(id -> !enviosConPlan.contains(id))
            .toList();

        return PlanningResult.builder()
            .planes(planes)
            .metrica(algorithm.getUltimaMetrica())
            .enviosSinRuta(enviosSinRuta)
            .build();
    }
}
