package com.tasf.backend.domain;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColapsoPunto {
    private int dia;
    /** Tipo de colapso: "SLA" (envíos retrasados) o "ALMACEN" (almacén saturado). */
    private String tipo;
    /** Porcentaje de SLA vencido (para tipo=SLA) o % de ocupación del almacén (para tipo=ALMACEN). */
    private double pctSlaVencido;
    /**
     * Número de envíos con SLA vencido en el momento del colapso. Es la magnitud que se muestra
     * para tipo=SLA: el criterio de colapso es "cualquier envío retrasado", así que el porcentaje
     * sobre el total siempre sale ~0 (p.ej. 2 de 73.154 = 0,0027% → "0.0%") y se lee como un error.
     */
    private int enviosSlaVencidos;
    /** Porcentaje de ocupación del almacén más crítico (para tipo=ALMACEN). */
    private double porcentajeOcupacion;
    private String aeropuertoMasCritico;
    @Builder.Default
    private List<String> topAeropuertos = new ArrayList<>();
}
