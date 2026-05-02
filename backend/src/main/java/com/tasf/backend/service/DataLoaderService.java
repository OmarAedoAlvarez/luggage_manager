package com.tasf.backend.service;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.parser.AirportParser;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.parser.FlightParser;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class DataLoaderService {
    private static final Logger log = LoggerFactory.getLogger(DataLoaderService.class);
    private static final Pattern IATA_PATTERN =
        Pattern.compile("_envios_([A-Za-z]{4})_\\.txt$", Pattern.CASE_INSENSITIVE);

    private final AirportParser airportParser;
    private final FlightParser flightParser;
    private final BaggageParser baggageParser;

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();
    private List<Envio> todosLosEnvios = new ArrayList<>();

    public DataLoaderService(AirportParser airportParser, FlightParser flightParser, BaggageParser baggageParser) {
        this.airportParser = airportParser;
        this.flightParser = flightParser;
        this.baggageParser = baggageParser;
    }

    @PostConstruct
    public void loadStaticData() {
        List<Aeropuerto> loadedAirports = new ArrayList<>();
        List<Vuelo> loadedFlights = new ArrayList<>();

        try (InputStream airportsStream = new ClassPathResource("data/aeropuertos.txt").getInputStream()) {
            loadedAirports = airportParser.parseAirports(airportsStream);
        } catch (IOException ex) {
            log.error("Unable to load static airport data", ex);
        }

        Map<String, String> continentByAirport = loadedAirports.stream()
            .collect(Collectors.toMap(Aeropuerto::getCodigoIATA, Aeropuerto::getContinente, (a, b) -> a));

        try (InputStream flightsStream = new ClassPathResource("data/planes_vuelo.txt").getInputStream()) {
            loadedFlights = flightParser.parseFlights(flightsStream, continentByAirport);
        } catch (IOException ex) {
            log.error("Unable to load static flight data", ex);
        }

        this.aeropuertos = List.copyOf(loadedAirports);
        this.vuelos = List.copyOf(loadedFlights);
        log.info("Loaded {} airports and {} flights", this.aeropuertos.size(), this.vuelos.size());

        List<Envio> loadedEnvios = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:data/Envios/_envios_*.txt");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                Matcher matcher = IATA_PATTERN.matcher(filename != null ? filename : "");
                if (!matcher.find()) {
                    log.warn("Skipping envio file with unexpected name: {}", filename);
                    continue;
                }
                String iata = matcher.group(1).toUpperCase();
                try (InputStream in = resource.getInputStream()) {
                    List<Envio> fileEnvios =
                        baggageParser.parseEnvios(in, iata, LocalDate.MIN, null, continentByAirport);
                    log.info("Loaded {} envios from {} (origin={})", fileEnvios.size(), filename, iata);
                    loadedEnvios.addAll(fileEnvios);
                } catch (IOException ex) {
                    log.error("Error reading envio file: {}", filename, ex);
                }
            }
        } catch (IOException ex) {
            log.error("Unable to scan envio classpath resources", ex);
        }
        this.todosLosEnvios = List.copyOf(loadedEnvios);
        log.info("Total envios loaded: {}", this.todosLosEnvios.size());
    }

    public List<Aeropuerto> getAeropuertos() {
        return aeropuertos;
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
    }

    public List<Envio> getTodosLosEnvios() {
        return todosLosEnvios;
    }
}
