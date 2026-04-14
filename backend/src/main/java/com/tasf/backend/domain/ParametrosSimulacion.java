package com.tasf.backend.domain;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParametrosSimulacion {
    private String algoritmo;
    private int diasSimulacion;
    private int capacidadAlmacen;
    private int capacidadVuelo;
    @Builder.Default
    private int minutosEscalaMinima = 10;
    @Builder.Default
    private int minutosRecogidaDestino = 10;
    @Builder.Default
    private int umbralSemaforoVerde = 60;
    @Builder.Default
    private int umbralSemaforoAmbar = 85;
    private LocalDate fechaInicio;
}
