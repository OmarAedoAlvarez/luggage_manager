package com.tasf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VueloDTO {
    private String codigoVuelo;
    private String origen;
    private String destino;
    private String tipo;
    private String estado;
    private int cargaActual;
    private int maletasAsignadas;
    private int capacidadTotal;
    private double fraction;
    private String horaSalida;
    private String horaLlegada;
    private boolean enUso;
}
