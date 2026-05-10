package com.tasf.backend.controller;

import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.dto.AeropuertoDTO;
import com.tasf.backend.dto.EnvioDTO;
import com.tasf.backend.dto.SimulationStateDTO;
import com.tasf.backend.dto.VueloDTO;
import com.tasf.backend.service.DataLoaderService;
import com.tasf.backend.simulation.SimulationEngine;
import com.tasf.backend.repository.EnvioRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationEngine simulationEngine;
    private final DataLoaderService dataLoaderService;
    private final EnvioRepository envioRepository;

    public SimulationController(
            SimulationEngine simulationEngine, 
            DataLoaderService dataLoaderService,
            EnvioRepository envioRepository) {
        this.simulationEngine = simulationEngine;
        this.dataLoaderService = dataLoaderService;
        this.envioRepository = envioRepository;
    }

    @PostMapping(value = "/simulation/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SimulationStateDTO> start(@RequestBody ParametrosSimulacion params) {
        log.info("startSimulation request: fechaInicio={}, dias={}, esColapso={}, algoritmo={}",
            params.getFechaInicio(), params.getDias(), params.getEsColapso(), params.getAlgoritmo());
        
        // Fase 4: Cargar solo los envíos necesarios para el periodo de simulación
        java.time.LocalDateTime inicio = params.getFechaInicio().atStartOfDay();
        java.time.LocalDateTime fin = inicio.plusDays(params.getDias());
        
        log.info("Querying envios from {} to {}", inicio, fin);
        List<Envio> enviosSimulacion = envioRepository.findByFechaHoraIngresoBetween(inicio, fin).stream()
            .map(e -> Envio.builder()
                .idEnvio(e.getIdPedido())
                .codigoAerolinea(e.getCodigoAerolinea())
                .aeropuertoOrigen(e.getIataOrigen())
                .aeropuertoDestino(e.getIataDestino())
                .fechaHoraIngreso(e.getFechaHoraIngreso())
                .cantidadMaletas(e.getCantidadMaletas())
                .sla(e.getSla())
                .estado(com.tasf.backend.domain.EstadoEnvio.valueOf(e.getEstado()))
                .build())
            .toList();
            
        log.info("Found {} envios for simulation period", enviosSimulacion.size());
        
        simulationEngine.inicializar(params, enviosSimulacion);
        log.info("startSimulation initialized successfully");
        return ResponseEntity.ok(simulationEngine.getEstado());
    }

    @PostMapping("/simulation/step")
    public ResponseEntity<SimulationStateDTO> step() {
        return ResponseEntity.ok(simulationEngine.avanzarDia());
    }

    @GetMapping("/simulation/state")
    public ResponseEntity<SimulationStateDTO> state() {
        if (!simulationEngine.estaInicializada()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(simulationEngine.getEstado());
    }

    @PostMapping("/simulation/reset")
    public ResponseEntity<Void> reset() {
        simulationEngine.reset();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/simulation/cancel-flight/{codigoVuelo}")
    public ResponseEntity<SimulationStateDTO> cancelFlight(@PathVariable String codigoVuelo) {
        simulationEngine.cancelarVueloManualmente(codigoVuelo);
        return ResponseEntity.ok(simulationEngine.getEstado());
    }

    @PostMapping("/simulation/cancel-envio/{idEnvio}")
    public ResponseEntity<SimulationStateDTO> cancelEnvio(@PathVariable String idEnvio) {
        simulationEngine.cancelarEnvioManualmente(idEnvio);
        return ResponseEntity.ok(simulationEngine.getEstado());
    }

    @GetMapping("/airports")
    public ResponseEntity<List<AeropuertoDTO>> airports() {
        return ResponseEntity.ok(simulationEngine.getAeropuertosEstado());
    }

    @GetMapping("/flights")
    public ResponseEntity<List<VueloDTO>> flights() {
        return ResponseEntity.ok(simulationEngine.getVuelosEstado());
    }

    @GetMapping("/envios")
    public ResponseEntity<List<EnvioDTO>> envios() {
        return ResponseEntity.ok(simulationEngine.getEnviosEstado());
    }

    @GetMapping("/envios/{id}")
    public ResponseEntity<EnvioDTO> envioById(@PathVariable("id") String id) {
        return simulationEngine.getEnvioPorId(id)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Envio not found"));
    }
}
