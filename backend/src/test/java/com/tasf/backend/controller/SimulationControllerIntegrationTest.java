package com.tasf.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startStepAndStateFlowWorks() throws Exception {
        String paramsJson = """
            {
              "algoritmo": "SIMULATED_ANNEALING",
              "dias": 3,
              "esColapso": false,
              "minutosEscalaMinima": 10,
              "minutosRecogidaDestino": 10,
              "umbralSemaforoVerde": 60,
              "umbralSemaforoAmbar": 85,
              "fechaInicio": "2026-01-02"
            }
            """;

        mockMvc.perform(post("/api/simulation/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paramsJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diaActual").value(1))
            .andExpect(jsonPath("$.aeropuertos").isArray())
            .andExpect(jsonPath("$.aeropuertos.length()").value(org.hamcrest.Matchers.greaterThan(0)))
            .andExpect(jsonPath("$.vuelos").isArray())
            .andExpect(jsonPath("$.vuelos.length()").value(org.hamcrest.Matchers.greaterThan(0)))
            .andExpect(jsonPath("$.envios").isArray())
            .andExpect(jsonPath("$.envios.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(post("/api/simulation/step"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diaActual").value(2));

        mockMvc.perform(get("/api/simulation/state"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diaActual").value(2))
            .andExpect(jsonPath("$.algoritmo").value("SIMULATED_ANNEALING"));

        mockMvc.perform(get("/api/airports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].codigoIATA").exists());

        mockMvc.perform(get("/api/flights"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].codigoVuelo").exists());

        MvcResult enviosResult = mockMvc.perform(get("/api/envios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].idEnvio").exists())
            .andReturn();

        String firstEnvioId = new ObjectMapper()
            .readTree(enviosResult.getResponse().getContentAsString())
            .get(0).get("idEnvio").asText();

        mockMvc.perform(get("/api/envios/" + firstEnvioId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idEnvio").value(firstEnvioId))
            .andExpect(jsonPath("$.planDetalle").exists());

        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/simulation/state"))
            .andExpect(status().isNoContent());
    }
}
