package com.tasf.backend.domain;

import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vuelo {
    private String codigoVuelo;
    private String origen;
    private String destino;
    private LocalTime horaSalida;
    private LocalTime horaLlegada;
    private int capacidadTotal;
    private String tipo;
    @Builder.Default
    private int cargaActual = 0;
    @Builder.Default
    private boolean cancelado = false;
}
