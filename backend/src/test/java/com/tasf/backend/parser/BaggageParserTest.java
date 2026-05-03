package com.tasf.backend.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tasf.backend.domain.Envio;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BaggageParserTest {

    private final BaggageParser parser = new BaggageParser();

    @Test
    void parseEnviosRespectsClosedDateWindow() {
        String input = String.join("\n",
            "E001-20260101-06-00-OERK-1-AIR",
            "E002-20260102-07-30-OERK-2-AIR",
            "E003-20260103-09-15-OERK-3-AIR",
            "E004-20260104-10-45-OERK-4-AIR"
        );

        List<Envio> result = parser.parseEnvios(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            "OJAI",
            LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 1, 3),
            Map.of(
                "OJAI", "asia",
                "OERK", "asia"
            )
        );

        assertEquals(2, result.size());
        assertEquals("OJAI-E002", result.get(0).getIdEnvio());
        assertEquals("OJAI-E003", result.get(1).getIdEnvio());
    }

    @Test
    void parseEnviosAllowsOpenEndedWindowForCollapse() {
        String input = String.join("\n",
            "E001-20260101-06-00-OERK-1-AIR",
            "E002-20260102-07-30-OERK-2-AIR",
            "E003-20260103-09-15-OERK-3-AIR"
        );

        List<Envio> result = parser.parseEnvios(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            "OJAI",
            LocalDate.of(2026, 1, 2),
            null,
            Map.of(
                "OJAI", "asia",
                "OERK", "asia"
            )
        );

        assertEquals(2, result.size());
        assertEquals("OJAI-E002", result.get(0).getIdEnvio());
        assertEquals("OJAI-E003", result.get(1).getIdEnvio());
    }
}