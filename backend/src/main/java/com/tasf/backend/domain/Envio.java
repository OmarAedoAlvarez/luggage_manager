package com.tasf.backend.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Envio {
    private String idEnvio;
    private String codigoAerolinea;
    private String aeropuertoOrigen;
    private String aeropuertoDestino;
    private LocalDateTime fechaHoraIngreso;
    private int cantidadMaletas;
    private int sla;
    private EstadoEnvio estado;
}
