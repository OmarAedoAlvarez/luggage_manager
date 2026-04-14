package com.tasf.backend.dto;

import com.tasf.backend.domain.PlanDeViaje;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvioDTO {
    private String idEnvio;
    private String codigoAerolinea;
    private String aeropuertoOrigen;
    private String aeropuertoDestino;
    private int cantidadMaletas;
    private String estado;
    private int sla;
    private String fechaHoraIngreso;
    private String planResumen;
    private String tiempoRestante;
    private PlanDeViaje planDetalle;
}
