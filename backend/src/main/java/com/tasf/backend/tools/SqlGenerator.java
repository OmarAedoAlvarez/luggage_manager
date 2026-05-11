package com.tasf.backend.tools;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.Vuelo;
import com.tasf.backend.parser.AirportParser;
import com.tasf.backend.parser.BaggageParser;
import com.tasf.backend.parser.FlightParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * standalone tool to generate seed_data.sql (master data) 
 * and a folder of split SQL files for envios.
 */
public class SqlGenerator {

    private static final String DATA_PATH = "src/main/resources/data/";
    private static final String OUTPUT_MASTER = "seed_data.sql";
    private static final String OUTPUT_ENVIOS_DIR = "sql_envios/";
    private static final Pattern IATA_PATTERN = Pattern.compile("_envios_([A-Za-z]{4})_\\.txt$", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        Map<String, String> continentByAirport = new HashMap<>();

        // 1. Generar Master Data (Aeropuertos y Vuelos)
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(OUTPUT_MASTER, StandardCharsets.UTF_8)))) {
            out.println("-- TASF.B2B Master Seed Data");
            out.println("SET FOREIGN_KEY_CHECKS = 0;");
            out.println();

            // Aeropuertos
            File airportFile = new File(DATA_PATH + "aeropuertos.txt");
            if (airportFile.exists()) {
                System.out.println("Generating Aeropuertos in " + OUTPUT_MASTER + "...");
                AirportParser airportParser = new AirportParser();
                List<Aeropuerto> aeropuertos;
                try (InputStream is = new FileInputStream(airportFile)) {
                    aeropuertos = airportParser.parseAirports(is);
                }
                continentByAirport = aeropuertos.stream()
                        .collect(Collectors.toMap(Aeropuerto::getCodigoIATA, Aeropuerto::getContinente));

                out.println("TRUNCATE TABLE aeropuertos;");
                out.println("INSERT INTO aeropuertos (codigo_iata, ciudad, pais, continente, huso, capacidad_almacen, lat, lng) VALUES");
                for (int i = 0; i < aeropuertos.size(); i++) {
                    Aeropuerto a = aeropuertos.get(i);
                    out.printf("('%s', '%s', '%s', '%s', %d, %d, %f, %f)%s\n",
                            a.getCodigoIATA(), escape(a.getCiudad()), escape(a.getPais()),
                            a.getContinente(), a.getHuso(), a.getCapacidadAlmacen(), a.getLat(), a.getLng(),
                            (i == aeropuertos.size() - 1) ? ";" : ",");
                }
                out.println();
            }

            // Vuelos
            File flightFile = new File(DATA_PATH + "planes_vuelo.txt");
            if (flightFile.exists()) {
                System.out.println("Generating Vuelos in " + OUTPUT_MASTER + "...");
                FlightParser flightParser = new FlightParser();
                List<Vuelo> vuelos;
                try (InputStream is = new FileInputStream(flightFile)) {
                    vuelos = flightParser.parseFlights(is, continentByAirport);
                }
                out.println("TRUNCATE TABLE vuelos;");
                for (int i = 0; i < vuelos.size(); i += 1000) {
                    int end = Math.min(i + 1000, vuelos.size());
                    out.println("INSERT INTO vuelos (codigo_vuelo, iata_origen, iata_destino, hora_salida, hora_llegada, capacidad_total, tipo) VALUES");
                    for (int j = i; j < end; j++) {
                        Vuelo v = vuelos.get(j);
                        out.printf("('%s', '%s', '%s', '%s', '%s', %d, '%s')%s\n",
                                v.getCodigoVuelo(), v.getOrigen(), v.getDestino(), v.getHoraSalida(), v.getHoraLlegada(),
                                v.getCapacidadTotal(), v.getTipo(), (j == end - 1) ? ";" : ",");
                    }
                }
                out.println();
            }

            out.println("SET FOREIGN_KEY_CHECKS = 1;");
            System.out.println("Successfully generated " + OUTPUT_MASTER);
        } catch (Exception e) { e.printStackTrace(); }

        // 2. Generar Envios Segmentados
        File enviosDir = new File(DATA_PATH + "Envios");
        File[] files = enviosDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));

        if (files != null && files.length > 0) {
            File outDir = new File(OUTPUT_ENVIOS_DIR);
            if (!outDir.exists()) outDir.mkdirs();

            System.out.println("Generating Split Envios in " + OUTPUT_ENVIOS_DIR + "...");
            BaggageParser baggageParser = new BaggageParser();

            for (File file : files) {
                Matcher matcher = IATA_PATTERN.matcher(file.getName());
                if (!matcher.find()) continue;
                String iata = matcher.group(1).toUpperCase();
                String sqlFile = OUTPUT_ENVIOS_DIR + "envios_" + iata + ".sql";

                try (PrintWriter envOut = new PrintWriter(new BufferedWriter(new FileWriter(sqlFile, StandardCharsets.UTF_8)))) {
                    envOut.println("-- Envios for " + iata);
                    envOut.println("SET FOREIGN_KEY_CHECKS = 0;");
                    // Note: We don't truncate because multiple files might share the table. 
                    // However, we clean simulation tables to avoid inconsistency.
                    envOut.println("TRUNCATE TABLE escalas_itinerario;");
                    envOut.println("TRUNCATE TABLE itinerarios;");
                    envOut.println("TRUNCATE TABLE metricas_ejecucion;");
                    envOut.println("TRUNCATE TABLE log_operaciones;");
                    // envOut.println("DELETE FROM envios WHERE iata_origen = '" + iata + "';"); // Clean only this airport
                    envOut.println();

                    List<Envio> envios;
                    try (InputStream is = new FileInputStream(file)) {
                        envios = baggageParser.parseEnvios(is, iata, LocalDate.MIN, null, continentByAirport);
                    }

                    if (envios.isEmpty()) continue;

                    for (int i = 0; i < envios.size(); i += 1000) {
                        int end = Math.min(i + 1000, envios.size());
                        envOut.println("INSERT INTO envios (id_pedido, codigo_aerolinea, iata_origen, iata_destino, cantidad_maletas, fecha_hora_ingreso, sla, estado) VALUES");
                        for (int j = i; j < end; j++) {
                            Envio e = envios.get(j);
                            envOut.printf("('%s', %s, '%s', '%s', %d, '%s', %d, 'PENDIENTE')%s\n",
                                    e.getIdEnvio(),
                                    e.getCodigoAerolinea() == null || e.getCodigoAerolinea().isBlank() ? "NULL" : "'" + e.getCodigoAerolinea() + "'",
                                    e.getAeropuertoOrigen(), e.getAeropuertoDestino(), e.getCantidadMaletas(),
                                    e.getFechaHoraIngreso().toString().replace("T", " "), e.getSla(),
                                    (j == end - 1) ? ";" : ",");
                        }
                    }
                    envOut.println("SET FOREIGN_KEY_CHECKS = 1;");
                    System.out.println("  Generated " + sqlFile + " (" + envios.size() + " envios)");
                } catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    private static String escape(String s) {
        return s.replace("'", "''");
    }
}
