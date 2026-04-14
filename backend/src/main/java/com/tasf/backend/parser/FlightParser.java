package com.tasf.backend.parser;

import com.tasf.backend.domain.Vuelo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FlightParser {
    private static final Logger log = LoggerFactory.getLogger(FlightParser.class);

    public List<Vuelo> parseFlights(InputStream inputStream, Map<String, String> continentByAirport) {
        List<Vuelo> vuelos = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                Vuelo vuelo = parseLine(line.trim(), continentByAirport);
                if (vuelo != null) {
                    vuelos.add(vuelo);
                } else {
                    log.warn("Skipping malformed flight line {}: {}", lineNumber, line);
                }
            }
        } catch (IOException ex) {
            log.error("Error reading flights data", ex);
        }

        log.info("Loaded {} flights", vuelos.size());
        return vuelos;
    }

    private Vuelo parseLine(String line, Map<String, String> continentByAirport) {
        String[] parts = line.split("-");
        if (parts.length != 5) {
            return null;
        }

        try {
            String origen = parts[0].trim();
            String destino = parts[1].trim();
            LocalTime salida = LocalTime.parse(parts[2].trim());
            LocalTime llegada = LocalTime.parse(parts[3].trim());
            int capacidad = Integer.parseInt(parts[4].trim());

            return Vuelo.builder()
                .codigoVuelo(buildCode(origen, destino, salida))
                .origen(origen)
                .destino(destino)
                .horaSalida(salida)
                .horaLlegada(llegada)
                .capacidadTotal(capacidad)
                .tipo(resolveFlightType(origen, destino, continentByAirport))
                .build();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String buildCode(String origen, String destino, LocalTime salida) {
        return origen + "-" + destino + "-" + salida;
    }

    private String resolveFlightType(String origen, String destino, Map<String, String> continentByAirport) {
        String originContinent = continentByAirport.get(origen);
        String destinationContinent = continentByAirport.get(destino);
        if (originContinent != null && originContinent.equals(destinationContinent)) {
            return "continental";
        }
        return "intercontinental";
    }
}
