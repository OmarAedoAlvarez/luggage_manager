package com.tasf.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startStepAndStateFlowWorks() throws Exception {
        String paramsJson = """
            {
              \"algoritmo\": \"SIMULATED_ANNEALING\",
                            \"dias\": 3,
                            \"esColapso\": false,
              \"capacidadAlmacen\": 1000,
              \"capacidadVuelo\": 360,
              \"minutosEscalaMinima\": 10,
              \"minutosRecogidaDestino\": 10,
              \"umbralSemaforoVerde\": 60,
              \"umbralSemaforoAmbar\": 85,
              \"fechaInicio\": \"2026-04-10\"
            }
            """;

        ClassPathResource resource = new ClassPathResource("data/_envios_SKBO_.txt");
        MockMultipartFile filePart = new MockMultipartFile(
            "files",
            "_envios_SKBO_.txt",
            MediaType.TEXT_PLAIN_VALUE,
            resource.getInputStream()
        );

        MockMultipartFile paramsPart = new MockMultipartFile(
            "params",
            "params",
            MediaType.APPLICATION_JSON_VALUE,
            paramsJson.getBytes()
        );

        mockMvc.perform(multipart("/api/simulation/start")
                .file(filePart)
                .file(paramsPart)
                .contentType(MediaType.MULTIPART_FORM_DATA_VALUE))
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

        mockMvc.perform(get("/api/envios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].idEnvio").exists());

        mockMvc.perform(get("/api/envios/E001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idEnvio").value("E001"))
            .andExpect(jsonPath("$.planDetalle").exists());

        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/simulation/state"))
            .andExpect(status().isNoContent());
    }
}
