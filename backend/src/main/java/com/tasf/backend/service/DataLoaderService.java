package com.tasf.backend.service;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.parser.AirportParser;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.parser.FlightParser;
import com.tasf.backend.repository.AeropuertoRepository;
import com.tasf.backend.repository.VueloRepository;
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

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;
    private final DatabaseSeederService databaseSeederService;

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();

    public DataLoaderService(
            AeropuertoRepository aeropuertoRepository,
            VueloRepository vueloRepository,
            DatabaseSeederService databaseSeederService) {
        this.aeropuertoRepository = aeropuertoRepository;
        this.vueloRepository = vueloRepository;
        this.databaseSeederService = databaseSeederService;
    }

    @PostConstruct
    public void init() {
        // Fase 3: Poblar la DB si está vacía
        databaseSeederService.seedDatabaseIfEmpty();
        
        // Fase 4: Cargar datos estáticos desde DB a memoria (para el motor)
        loadStaticDataFromDb();
    }

    private void loadStaticDataFromDb() {
        log.info("Loading static data from database...");
        
        this.aeropuertos = aeropuertoRepository.findAll().stream()
            .map(e -> Aeropuerto.builder()
                .codigoIATA(e.getCodigoIata())
                .nombre(e.getCiudad() + " Airport") // Mantenemos la lógica del parser original
                .ciudad(e.getCiudad())
                .pais(e.getPais())
                .continente(e.getContinente())
                .huso(e.getHuso())
                .capacidadAlmacen(e.getCapacidadAlmacen())
                .lat(e.getLat())
                .lng(e.getLng())
                .build())
            .toList();

        this.vuelos = vueloRepository.findAll().stream()
            .map(e -> Vuelo.builder()
                .codigoVuelo(e.getCodigoVuelo())
                .origen(e.getIataOrigen())
                .destino(e.getIataDestino())
                .horaSalida(e.getHoraSalida())
                .horaLlegada(e.getHoraLlegada())
                .capacidadTotal(e.getCapacidadTotal())
                .tipo(e.getTipo())
                .build())
            .toList();

        log.info("Loaded {} airports and {} flights from DB", this.aeropuertos.size(), this.vuelos.size());
    }

    public List<Aeropuerto> getAeropuertos() {
        return aeropuertos;
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
    }

    // Nota: El método getTodosLosEnvios() se elimina porque ya no cargamos todo en memoria.
    // El SimulationController ahora debe pedir los envíos por rango de fechas al repositorio.
}

