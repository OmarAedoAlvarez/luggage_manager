package com.tasf.backend.service;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.entity.AeropuertoEntity;
import com.tasf.backend.entity.EnvioEntity;
import com.tasf.backend.entity.VueloEntity;
import com.tasf.backend.parser.AirportParser;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.parser.FlightParser;
import com.tasf.backend.repository.AeropuertoRepository;
import com.tasf.backend.repository.EnvioRepository;
import com.tasf.backend.repository.VueloRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DatabaseSeederService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSeederService.class);
    private static final Pattern IATA_PATTERN =
        Pattern.compile("_envios_([A-Za-z]{4})_\\.txt$", Pattern.CASE_INSENSITIVE);
    private static final int BATCH_SIZE = 5000;

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;
    private final EnvioRepository envioRepository;
    private final AirportParser airportParser;
    private final FlightParser flightParser;
    private final BaggageParser baggageParser;

    public DatabaseSeederService(
            AeropuertoRepository aeropuertoRepository,
            VueloRepository vueloRepository,
            EnvioRepository envioRepository,
            AirportParser airportParser,
            FlightParser flightParser,
            BaggageParser baggageParser) {
        this.aeropuertoRepository = aeropuertoRepository;
        this.vueloRepository = vueloRepository;
        this.envioRepository = envioRepository;
        this.airportParser = airportParser;
        this.flightParser = flightParser;
        this.baggageParser = baggageParser;
    }

    // NO @Transactional aquí — cada sub-método maneja su propia transacción.
    // Antes todo estaba en una transacción: los aeropuertos quedaban en el buffer de
    // Hibernate sin commitear, y cuando seedFlights() hacía un findAll(), el auto-flush
    // intentaba insertar y generaba un deadlock.
    public void seedDatabaseIfEmpty() {
        if (aeropuertoRepository.count() == 0) {
            seedAirports();
        } else {
            log.info("Aeropuertos already present in DB, skipping seeding.");
        }

        if (vueloRepository.count() == 0) {
            seedFlights();
        } else {
            log.info("Vuelos already present in DB, skipping seeding.");
        }

        if (envioRepository.count() == 0) {
            seedEnvios();
        } else {
            log.info("Envios already present in DB, skipping seeding.");
        }
    }

    @Transactional
    public void seedAirports() {
        log.info("Seeding airports from file...");
        try (InputStream is = new ClassPathResource("data/aeropuertos.txt").getInputStream()) {
            List<Aeropuerto> domainAirports = airportParser.parseAirports(is);
            List<AeropuertoEntity> entities = domainAirports.stream()
                .map(this::mapToEntity)
                .collect(Collectors.toList());
            aeropuertoRepository.saveAll(entities);
            log.info("Successfully seeded {} airports.", entities.size());
        } catch (IOException e) {
            log.error("Failed to seed airports", e);
        }
    }

    @Transactional
    public void seedFlights() {
        log.info("Seeding flights from file...");
        Map<String, String> continentByAirport = aeropuertoRepository.findAll().stream()
            .collect(Collectors.toMap(AeropuertoEntity::getCodigoIata, AeropuertoEntity::getContinente));

        try (InputStream is = new ClassPathResource("data/planes_vuelo.txt").getInputStream()) {
            List<Vuelo> domainFlights = flightParser.parseFlights(is, continentByAirport);
            List<VueloEntity> entities = domainFlights.stream()
                .map(this::mapToEntity)
                .collect(Collectors.toList());
            vueloRepository.saveAll(entities);
            log.info("Successfully seeded {} flights.", entities.size());
        } catch (IOException e) {
            log.error("Failed to seed flights", e);
        }
    }

    @Transactional
    public void seedEnvios() {
        log.info("Seeding envios from files (this may take a while)...");
        Map<String, String> continentByAirport = aeropuertoRepository.findAll().stream()
            .collect(Collectors.toMap(AeropuertoEntity::getCodigoIata, AeropuertoEntity::getContinente));

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:data/Envios/_envios_*.txt");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                Matcher matcher = IATA_PATTERN.matcher(filename != null ? filename : "");
                if (!matcher.find()) continue;

                String iata = matcher.group(1).toUpperCase();
                log.info("Processing file: {} (origin={})", filename, iata);

                try (InputStream in = resource.getInputStream()) {
                    List<Envio> domainEnvios = baggageParser.parseEnvios(in, iata, java.time.LocalDate.MIN, null, continentByAirport);
                    saveEnviosInBatches(domainEnvios);
                }
            }
            log.info("Finished seeding envios.");
        } catch (IOException e) {
            log.error("Failed to scan envios directory", e);
        }
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

    private AeropuertoEntity mapToEntity(Aeropuerto a) {
        return AeropuertoEntity.builder()
            .codigoIata(a.getCodigoIATA())
            .ciudad(a.getCiudad())
            .pais(a.getPais())
            .continente(a.getContinente())
            .huso(a.getHuso())
            .capacidadAlmacen(a.getCapacidadAlmacen())
            .lat(a.getLat())
            .lng(a.getLng())
            .build();
    }

    private VueloEntity mapToEntity(Vuelo v) {
        return VueloEntity.builder()
            .codigoVuelo(v.getCodigoVuelo())
            .iataOrigen(v.getOrigen())
            .iataDestino(v.getDestino())
            .horaSalida(v.getHoraSalida())
            .horaLlegada(v.getHoraLlegada())
            .capacidadTotal(v.getCapacidadTotal())
            .tipo(v.getTipo())
            .build();
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
