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
public class Escala {
    private int orden;
    private String codigoAeropuerto;
    private LocalDateTime horaLlegadaEst;
    private LocalDateTime horaSalidaEst;
    private String codigoVuelo;
    private boolean completada;
}
