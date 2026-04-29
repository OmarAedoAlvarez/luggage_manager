package com.tasf.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tasf.backend.domain.Aeropuerto;
import com.tasf.backend.domain.Envio;
import com.tasf.backend.domain.EstadoEnvio;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.domain.PlanningResult;
import com.tasf.backend.domain.Vuelo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PlanningServiceIntegrationTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private DataLoaderService dataLoaderService;

    private List<Envio> sampleEnvios;

    @BeforeEach
    void setUp() {
        List<Aeropuerto> aeropuertos = dataLoaderService.getAeropuertos();
        List<Vuelo> vuelos = dataLoaderService.getVuelos();
        Map<String, String> continentByAirport = aeropuertos.stream()
            .collect(Collectors.toMap(Aeropuerto::getCodigoIATA, Aeropuerto::getContinente, (a, b) -> a));

        String origin = "SKBO";
        Set<String> destinations = vuelos.stream()
            .filter(v -> origin.equals(v.getOrigen()))
            .map(Vuelo::getDestino)
            .filter(dest -> !origin.equals(dest))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> selectedDestinations = destinations.stream().limit(10).toList();
        sampleEnvios = new ArrayList<>();
        for (int i = 0; i < selectedDestinations.size(); i++) {
            String destination = selectedDestinations.get(i);
            int sla = continentByAirport.getOrDefault(origin, "").equals(continentByAirport.getOrDefault(destination, "")) ? 1 : 2;
            sampleEnvios.add(Envio.builder()
                .idEnvio("TEST-ENVIO-" + i)
                .codigoAerolinea("TEST-AIR")
                .aeropuertoOrigen(origin)
                .aeropuertoDestino(destination)
                .fechaHoraIngreso(LocalDateTime.of(2026, 4, 10, 8, 0).plusMinutes(i))
                .cantidadMaletas(5)
                .sla(sla)
                .estado(EstadoEnvio.PENDIENTE)
                .build());
        }
        assertEquals(10, sampleEnvios.size(), "Expected exactly 10 sample envios");
    }

    @Test
    void planificaConSimulatedAnnealing() {
        ParametrosSimulacion params = baseParams("SIMULATED_ANNEALING");

        PlanningResult result = planningService.planificar(
            copyEnvios(sampleEnvios),
            dataLoaderService.getVuelos(),
            dataLoaderService.getAeropuertos(),
            params
        );

        assertNotNull(result);
        assertNotNull(result.getMetrica());
        assertEquals("SIMULATED_ANNEALING", result.getMetrica().getAlgoritmoUsado());
        assertTrue(result.getMetrica().getTiempoEjecucionMs() > 0);
        assertTrue(result.getMetrica().getRutasEvaluadas() > 0);
        assertFalse(result.getPlanes().isEmpty());
        assertTrue(result.getPlanes().stream().allMatch(plan -> "SIMULATED_ANNEALING".equals(plan.getAlgoritmoUsado())));
        System.out.println("METRICA_SAMPLE_SA=" + result.getMetrica());
    }

    @Test
    void planificaConTabuSearchDirecto() {
        ParametrosSimulacion params = baseParams("TABU_SEARCH");

        PlanningResult result = planningService.planificar(
            copyEnvios(sampleEnvios),
            dataLoaderService.getVuelos(),
            dataLoaderService.getAeropuertos(),
            params
        );

        assertNotNull(result);
        assertNotNull(result.getMetrica());
        assertEquals("TABU_SEARCH", result.getMetrica().getAlgoritmoUsado());
        assertTrue(result.getMetrica().getTiempoEjecucionMs() > 0);
        assertTrue(result.getMetrica().getRutasEvaluadas() > 0);
        assertFalse(result.getPlanes().isEmpty());
        assertTrue(result.getPlanes().stream().allMatch(plan -> "TABU_SEARCH".equals(plan.getAlgoritmoUsado())));
        System.out.println("METRICA_SAMPLE_TS_DIRECTO=" + result.getMetrica());
    }

    @Test
    void planificaConIncidenciaUsaTabuSearch() {
        ParametrosSimulacion params = baseParams("SIMULATED_ANNEALING");

        PlanningResult result = planningService.planificarConIncidencia(
            copyEnvios(sampleEnvios),
            dataLoaderService.getVuelos(),
            dataLoaderService.getAeropuertos(),
            params
        );

        assertNotNull(result);
        assertNotNull(result.getMetrica());
        assertEquals("TABU_SEARCH", result.getMetrica().getAlgoritmoUsado());
        assertTrue(result.getMetrica().getTiempoEjecucionMs() > 0);
        assertTrue(result.getMetrica().getRutasEvaluadas() > 0);
        assertFalse(result.getPlanes().isEmpty());
        assertTrue(result.getPlanes().stream().allMatch(plan -> "TABU_SEARCH".equals(plan.getAlgoritmoUsado())));
        System.out.println("METRICA_SAMPLE_TS=" + result.getMetrica());
    }

    private ParametrosSimulacion baseParams(String algorithm) {
        return ParametrosSimulacion.builder()
            .algoritmo(algorithm)
            .diasSimulacion(2)
            .capacidadAlmacen(1000)
            .capacidadVuelo(360)
            .fechaInicio(LocalDate.of(2026, 4, 10))
            .build();
    }

    private List<Envio> copyEnvios(List<Envio> source) {
        return source.stream().map(envio -> Envio.builder()
            .idEnvio(envio.getIdEnvio())
            .codigoAerolinea(envio.getCodigoAerolinea())
            .aeropuertoOrigen(envio.getAeropuertoOrigen())
            .aeropuertoDestino(envio.getAeropuertoDestino())
            .fechaHoraIngreso(envio.getFechaHoraIngreso())
            .cantidadMaletas(envio.getCantidadMaletas())
            .sla(envio.getSla())
            .estado(envio.getEstado())
            .build()).toList();
    }
}
