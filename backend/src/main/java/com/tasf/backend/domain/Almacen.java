package com.tasf.backend.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Almacen {
    private String id;
    private String codigoAeropuerto;
    private int capacidadMaxima;
    private int ocupacionActual;
}
