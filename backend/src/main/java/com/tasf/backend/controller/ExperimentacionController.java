package com.tasf.backend.controller;

import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.dto.SimulationStateDTO;
import com.tasf.backend.service.ExperimentacionService;
import com.tasf.backend.simulation.SimulationEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/experimentos")
public class ExperimentacionController {

    private final SimulationEngine simulationEngine;
    private final ExperimentacionService experimentacionService;

    public ExperimentacionController(
        SimulationEngine simulationEngine,
        ExperimentacionService experimentacionService
    ) {
        this.simulationEngine = simulationEngine;
        this.experimentacionService = experimentacionService;
    }

    @PostMapping("/registrar")
    public ResponseEntity<Map<String, String>> registrar() {
        SimulationStateDTO state = simulationEngine.getEstado();
        if (!state.isFinalizada()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La simulación no ha finalizado aún.");
        }

        ParametrosSimulacion params = simulationEngine.getParams();
        MetricaAlgoritmo metrica = state.getMetrica();

        experimentacionService.registrarExperimento(state, params, metrica);
        return ResponseEntity.ok(Map.of("mensaje", "Experimento registrado."));
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export() {
        Path csvPath = experimentacionService.getCsvPath();
        if (!Files.exists(csvPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No hay experimentos registrados aún.");
        }

        Resource resource = new PathResource(csvPath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(
            ContentDisposition.attachment().filename("experimentos.csv").build());

        return new ResponseEntity<>(resource, headers, HttpStatus.OK);
    }
}
