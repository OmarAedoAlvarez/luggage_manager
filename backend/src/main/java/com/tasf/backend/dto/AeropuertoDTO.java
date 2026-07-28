package com.tasf.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AeropuertoDTO {
    private String codigoIATA;
    private String nombre;
    private String ciudad;
    private String continente;
    private double lat;
    private double lng;
    private int huso;
    private int capacidadAlmacen;
    private int ocupacionActual;
    private String semaforo;
    private int maletasRecibidas;
    private int maletasEnviadas;
    private double ocupacionPromedio;
    private double ocupacionMaxima;
    private int maletasEnAlmacenLocal;
    private int maletasEnTransitoEntrantes;
    private int ocupacionInicioDia;
    private List<OcupacionEventoDTO> eventosOcupacionDia;
    private String nextDeparture;
    private String nextArrival;
}
