package com.tasf.backend.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.tasf.backend.domain.MetricaAlgoritmo;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationStateDTO {
    private int diaActual;
    private int totalDias;
    private String fechaSimulada;
    private String algoritmo;
    private MetricaAlgoritmo metrica;
    private boolean enEjecucion;
    private boolean finalizada;
    private List<AeropuertoDTO> aeropuertos;
    private List<VueloDTO> vuelos;
    private List<EnvioDTO> envios;
    private KpisDTO kpis;
    private List<ThroughputDiaDTO> throughputHistorial;
    private List<String> logOperaciones;
}
