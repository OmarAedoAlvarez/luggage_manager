package com.tasf.backend.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Aeropuerto {
    private String codigoIATA;
    private String nombre;
    private String ciudad;
    private String pais;
    private String continente;
    private int huso;
    private int capacidadAlmacen;
    private double lat;
    private double lng;
    @Builder.Default
    private int ocupacionActual = 0;
}
