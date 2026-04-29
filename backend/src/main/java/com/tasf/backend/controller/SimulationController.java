package com.tasf.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.dto.AeropuertoDTO;
import com.tasf.backend.dto.EnvioDTO;
import com.tasf.backend.dto.SimulationStateDTO;
import com.tasf.backend.dto.VueloDTO;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.service.DataLoaderService;
import com.tasf.backend.simulation.SimulationEngine;
import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);
    private static final Pattern ORIGIN_PATTERN = Pattern.compile("_envios_([A-Za-z]{4})_?.*\\.txt$", Pattern.CASE_INSENSITIVE);

    private final SimulationEngine simulationEngine;
    private final BaggageParser baggageParser;
    private final DataLoaderService dataLoaderService;
    private final ObjectMapper objectMapper;

    public SimulationController(
        SimulationEngine simulationEngine,
        BaggageParser baggageParser,
        DataLoaderService dataLoaderService,
        ObjectMapper objectMapper
    ) {
        this.simulationEngine = simulationEngine;
        this.baggageParser = baggageParser;
        this.dataLoaderService = dataLoaderService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/simulation/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SimulationStateDTO> start(
        @RequestPart("params") String paramsJson,
        @RequestPart("files") MultipartFile[] files
    ) {
        try {
            log.info("startSimulation request received: paramsBytes={}, fileCount={}",
                paramsJson != null ? paramsJson.length() : 0,
                files != null ? files.length : 0);
            ParametrosSimulacion params = objectMapper.readValue(paramsJson, ParametrosSimulacion.class);
            log.info("startSimulation params parsed: fechaInicio={}, dias={}, diasSimulacion={}, esColapso={}, algoritmo={}",
                params.getFechaInicio(),
                params.getDias(),
                params.getDiasSimulacion(),
                params.getEsColapso(),
                params.getAlgoritmo());
            if (files == null || files.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one baggage file is required");
            }

            Map<String, String> continentByAirport = dataLoaderService.getAeropuertos().stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIATA, Aeropuerto::getContinente, (a, b) -> a));

            LocalDate dateFrom = params.getFechaInicio();
            int diasSolicitados = resolveDiasSolicitados(params);
            boolean esColapso = Boolean.TRUE.equals(params.getEsColapso());
            LocalDate dateTo = esColapso ? null : params.getFechaInicio().plusDays(diasSolicitados - 1);
            List<Envio> envios = new ArrayList<>();

            for (MultipartFile file : files) {
                String origin = extractOrigin(file.getOriginalFilename());
                log.info("startSimulation processing file: name={}, size={}, origin={}",
                    file.getOriginalFilename(),
                    file.getSize(),
                    origin);
                envios.addAll(baggageParser.parseEnvios(file.getInputStream(), origin, dateFrom, dateTo, continentByAirport));
            }

            int diasSimulacionResueltos = esColapso
                ? resolveDiasColapso(dateFrom, envios)
                : diasSolicitados;
            params.setDias(diasSolicitados);
            params.setDiasSimulacion(diasSimulacionResueltos);

            log.info("startSimulation envios loaded: count={}, diasSimulacionResueltos={}", envios.size(), diasSimulacionResueltos);
            simulationEngine.inicializar(params, envios);
            log.info("startSimulation initialized successfully");
            return ResponseEntity.ok(simulationEngine.getEstado());
        } catch (IOException ex) {
            log.error("Failed to start simulation", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload for simulation start");
        } catch (RuntimeException ex) {
            log.error("Unexpected error while starting simulation", ex);
            throw ex;
        }
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

    private String extractOrigin(String filename) {
        if (filename == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename is required");
        }
        Matcher matcher = ORIGIN_PATTERN.matcher(filename);
        if (!matcher.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Filename must follow _envios_XXXX.txt pattern: " + filename);
        }
        return matcher.group(1).toUpperCase();
    }

    private int resolveDiasSolicitados(ParametrosSimulacion params) {
        if (params.getDias() != null && params.getDias() > 0) {
            return params.getDias();
        }
        if (params.getDiasSimulacion() > 0) {
            return params.getDiasSimulacion();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dias must be greater than zero");
    }

    private int resolveDiasColapso(LocalDate dateFrom, List<Envio> envios) {
        LocalDate lastDate = envios.stream()
            .map(Envio::getFechaHoraIngreso)
            .map(java.time.LocalDateTime::toLocalDate)
            .max(LocalDate::compareTo)
            .orElse(dateFrom);
        return (int) Math.max(1, ChronoUnit.DAYS.between(dateFrom, lastDate) + 1);
    }
}
