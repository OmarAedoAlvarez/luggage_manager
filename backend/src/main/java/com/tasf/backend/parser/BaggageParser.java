package com.tasf.backend.parser;

import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BaggageParser {
    private static final Logger log = LoggerFactory.getLogger(BaggageParser.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    public List<Envio> parseEnvios(
        InputStream inputStream,
        String originAirport,
        LocalDate dateFrom,
        LocalDate dateTo,
        Map<String, String> continentByAirport
    ) {
        List<Envio> envios = new ArrayList<>();
        int parsed = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                Envio envio = parseLine(line.trim(), originAirport, dateFrom, dateTo, continentByAirport);
                if (envio != null) {
                    envios.add(envio);
                    parsed++;
                } else if (!line.isBlank()) {
                    log.warn("Skipping malformed or out-of-range baggage line {}: {}", lineNumber, line);
                    skipped++;
                }
            }
        } catch (IOException ex) {
            log.error("Error reading baggage data", ex);
        }

        log.info("Loaded {} envios from baggage file (skipped={})", parsed, skipped);

        return envios;
    }

    private Envio parseLine(
        String line,
        String originAirport,
        LocalDate dateFrom,
        LocalDate dateTo,
        Map<String, String> continentByAirport
    ) {
        String[] parts = line.split("-");
        if (parts.length != 7) {
            return null;
        }

        try {
            LocalDate date = LocalDate.parse(parts[1].trim(), DATE_FORMATTER);
            if (date.isBefore(dateFrom)) {
                return null;
            }
            if (dateTo != null && date.isAfter(dateTo)) {
                return null;
            }

            LocalTime time = LocalTime.of(
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim())
            );

            String destinationAirport = parts[4].trim();

            return Envio.builder()
                .idEnvio(parts[0].trim())
                .codigoAerolinea(parts[6].trim())
                .aeropuertoOrigen(originAirport)
                .aeropuertoDestino(destinationAirport)
                .fechaHoraIngreso(LocalDateTime.of(date, time))
                .cantidadMaletas(Integer.parseInt(parts[5].trim()))
                .sla(resolveSla(originAirport, destinationAirport, continentByAirport))
                .estado(EstadoEnvio.PENDIENTE)
                .build();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int resolveSla(String originAirport, String destinationAirport, Map<String, String> continentByAirport) {
        String originContinent = continentByAirport.get(originAirport);
        String destinationContinent = continentByAirport.get(destinationAirport);
        if (originContinent != null && originContinent.equals(destinationContinent)) {
            return 1;
        }
        return 2;
    }
}
