package com.tasf.backend.service;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.entity.EnvioEntity;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.repository.AeropuertoRepository;
import com.tasf.backend.repository.EnvioRepository;
import com.tasf.backend.simulation.SimulationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EnvioUploadService {
    private static final Logger log = LoggerFactory.getLogger(EnvioUploadService.class);
    private static final Pattern IATA_PATTERN =
        Pattern.compile("_envios_([A-Za-z]{4})_\\.txt$", Pattern.CASE_INSENSITIVE);
    private static final int BATCH_SIZE = 5000;

    private final EnvioRepository envioRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final BaggageParser baggageParser;
    private final SimulationEngine simulationEngine;

    public EnvioUploadService(
            EnvioRepository envioRepository,
            AeropuertoRepository aeropuertoRepository,
            BaggageParser baggageParser,
            SimulationEngine simulationEngine) {
        this.envioRepository = envioRepository;
        this.aeropuertoRepository = aeropuertoRepository;
        this.baggageParser = baggageParser;
        this.simulationEngine = simulationEngine;
    }

    @Transactional
    public List<Envio> processUpload(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        Matcher matcher = IATA_PATTERN.matcher(filename != null ? filename : "");
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid filename format. Expected: _envios_XXXX_.txt");
        }

        String iata = matcher.group(1).toUpperCase();
        Map<String, String> continentByAirport = aeropuertoRepository.findAll().stream()
            .collect(Collectors.toMap(e -> e.getCodigoIata(), e -> e.getContinente()));

        List<Envio> domainEnvios;
        try (InputStream in = file.getInputStream()) {
            domainEnvios = baggageParser.parseEnvios(in, iata, java.time.LocalDate.MIN, null, continentByAirport);
        }

        // Filtrar duplicados (por idPedido de dominio)
        List<Envio> newDomainEnvios = domainEnvios.stream()
            .filter(e -> !envioRepository.existsByIdPedido(e.getIdEnvio()))
            .toList();

        if (newDomainEnvios.isEmpty()) {
            log.info("No new envios found in file (all were duplicates).");
            return List.of();
        }

        // Persistir en lotes
        saveEnviosInBatches(newDomainEnvios);
        log.info("Successfully uploaded and saved {} new envios from {}", newDomainEnvios.size(), filename);

        // Notificar al motor si está en ejecución
        if (simulationEngine.estaInicializada()) {
            simulationEngine.agregarNuevosEnvios(newDomainEnvios);
        }

        return newDomainEnvios;
    }

    private void saveEnviosInBatches(List<Envio> domainEnvios) {
        List<EnvioEntity> batch = new ArrayList<>();
        for (int i = 0; i < domainEnvios.size(); i++) {
            batch.add(mapToEntity(domainEnvios.get(i)));
            if (batch.size() >= BATCH_SIZE) {
                envioRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            envioRepository.saveAll(batch);
        }
    }

    private EnvioEntity mapToEntity(Envio e) {
        return EnvioEntity.builder()
            .idPedido(e.getIdEnvio())
            .codigoAerolinea(e.getCodigoAerolinea())
            .iataOrigen(e.getAeropuertoOrigen())
            .iataDestino(e.getAeropuertoDestino())
            .cantidadMaletas(e.getCantidadMaletas())
            .fechaHoraIngreso(e.getFechaHoraIngreso())
            .sla(e.getSla())
            .estado(e.getEstado().name())
            .build();
    }
}
