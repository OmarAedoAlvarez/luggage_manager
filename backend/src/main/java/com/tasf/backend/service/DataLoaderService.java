package com.tasf.backend.service;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.parser.AirportParser;
import com.tasf.backend.parser.FlightParser;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class DataLoaderService {
    private static final Logger log = LoggerFactory.getLogger(DataLoaderService.class);

    private final AirportParser airportParser;
    private final FlightParser flightParser;

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();

    public DataLoaderService(AirportParser airportParser, FlightParser flightParser) {
        this.airportParser = airportParser;
        this.flightParser = flightParser;
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
    }

    public List<Aeropuerto> getAeropuertos() {
        return aeropuertos;
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
    }
}
