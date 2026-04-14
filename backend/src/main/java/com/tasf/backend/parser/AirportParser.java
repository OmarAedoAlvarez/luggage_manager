package com.tasf.backend.parser;

import com.tasf.backend.domain.Aeropuerto;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AirportParser {
    private static final Logger log = LoggerFactory.getLogger(AirportParser.class);

    private static final Pattern AIRPORT_PATTERN = Pattern.compile(
        "^\\s*(\\d+)\\s+([A-Z]{4})\\s+(.+?)\\s{2,}(.+?)\\s{2,}(\\S+)\\s+([+-]?\\d+)\\s+(\\d+)\\s+Latitude:.*$"
    );

    private static final Pattern DMS_PATTERN = Pattern.compile(
        "Latitude:\\s*(\\d{1,2})\\D+?(\\d{1,2})\\D+?(\\d{1,2})\\D+([NS])\\s+Longitude:\\s*(\\d{1,3})\\D+?(\\d{1,2})\\D+?(\\d{1,2})\\D+([EW])"
    );

    private static final Set<String> EUROPE_COUNTRIES = Set.of(
        "albania", "alemania", "austria", "belgica", "bielorrusia", "bulgaria",
        "checa", "croacia", "dinamarca", "holanda"
    );

    private static final Set<String> ASIA_COUNTRIES = Set.of(
        "india", "siria", "arabia saudita", "emiratos a.u", "afganistan", "oman",
        "yemen", "pakistan", "azerbaiyan", "jordania"
    );

    private static final Set<String> AMERICAS_COUNTRIES = Set.of(
        "colombia", "ecuador", "venezuela", "brasil", "peru", "bolivia",
        "chile", "argentina", "paraguay", "uruguay"
    );

    public List<Aeropuerto> parseAirports(InputStream inputStream) {
        List<Aeropuerto> result = new ArrayList<>();

        try {
            byte[] content = inputStream.readAllBytes();
            String text = new String(content, detectCharset(content));
            String[] lines = text.split("\\R");

            int lineNumber = 0;
            for (String rawLine : lines) {
                lineNumber++;
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isBlank() || shouldSkipLine(line)) {
                    continue;
                }

                Aeropuerto parsed = parseLine(line);
                if (parsed != null) {
                    result.add(parsed);
                } else {
                    log.warn("Skipping malformed airport line {}: {}", lineNumber, rawLine);
                }
            }

            log.info("Loaded {} airports", result.size());
            return result;
        } catch (IOException ex) {
            log.error("Error reading airport data", ex);
            return result;
        }
    }

    private Aeropuerto parseLine(String line) {
        Matcher baseMatcher = AIRPORT_PATTERN.matcher(line);
        Matcher dmsMatcher = DMS_PATTERN.matcher(line);
        if (!baseMatcher.matches() || !dmsMatcher.find()) {
            return null;
        }

        String codigoIata = baseMatcher.group(2);
        String ciudad = baseMatcher.group(3).trim();
        String pais = baseMatcher.group(4).trim();
        int gmt = Integer.parseInt(baseMatcher.group(6));
        int capacidad = Integer.parseInt(baseMatcher.group(7));

        double lat = toDecimal(
            Integer.parseInt(dmsMatcher.group(1)),
            Integer.parseInt(dmsMatcher.group(2)),
            Integer.parseInt(dmsMatcher.group(3)),
            dmsMatcher.group(4)
        );

        double lng = toDecimal(
            Integer.parseInt(dmsMatcher.group(5)),
            Integer.parseInt(dmsMatcher.group(6)),
            Integer.parseInt(dmsMatcher.group(7)),
            dmsMatcher.group(8)
        );

        return Aeropuerto.builder()
            .codigoIATA(codigoIata)
            .nombre(ciudad + " Airport")
            .ciudad(ciudad)
            .pais(pais)
            .continente(detectContinent(gmt, pais))
            .huso(gmt)
            .capacidadAlmacen(capacidad)
            .lat(lat)
            .lng(lng)
            .build();
    }

    private boolean shouldSkipLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("america del sur")
            || lower.contains("europa")
            || lower.contains("asia")
            || lower.startsWith("***")
            || lower.contains("gmt")
            || line.chars().allMatch(ch -> ch == '*');
    }

    private Charset detectCharset(byte[] content) {
        if (content.length >= 2) {
            int b0 = content[0] & 0xFF;
            int b1 = content[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
        }

        int nullByteCount = 0;
        int sampleLength = Math.min(content.length, 512);
        for (int i = 0; i < sampleLength; i++) {
            if (content[i] == 0) {
                nullByteCount++;
            }
        }
        if (sampleLength > 0 && ((double) nullByteCount / sampleLength) > 0.2d) {
            return StandardCharsets.UTF_16LE;
        }

        return StandardCharsets.UTF_8;
    }

    private String detectContinent(int gmt, String country) {
        String normalizedCountry = country.toLowerCase(Locale.ROOT).trim();

        if (gmt >= -8 && gmt <= -3) {
            return "americas";
        }
        if (gmt >= 4 && gmt <= 9) {
            return "asia";
        }
        if (gmt >= 0 && gmt <= 2) {
            return "europa";
        }

        if (gmt == 3) {
            if (EUROPE_COUNTRIES.contains(normalizedCountry)) {
                return "europa";
            }
            if (ASIA_COUNTRIES.contains(normalizedCountry)) {
                return "asia";
            }
        }

        if (AMERICAS_COUNTRIES.contains(normalizedCountry)) {
            return "americas";
        }
        if (EUROPE_COUNTRIES.contains(normalizedCountry)) {
            return "europa";
        }
        if (ASIA_COUNTRIES.contains(normalizedCountry)) {
            return "asia";
        }

        log.warn("Unknown continent for country '{}' and GMT '{}'. Defaulting to americas", country, gmt);
        return "americas";
    }

    private double toDecimal(int degrees, int minutes, int seconds, String hemisphere) {
        double decimal = degrees + (minutes / 60.0d) + (seconds / 3600.0d);
        if ("S".equalsIgnoreCase(hemisphere) || "W".equalsIgnoreCase(hemisphere)) {
            return -decimal;
        }
        return decimal;
    }
}
