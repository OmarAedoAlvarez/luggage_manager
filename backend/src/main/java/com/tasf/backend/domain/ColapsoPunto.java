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
    /** Porcentaje de ocupación del almacén más crítico (para tipo=ALMACEN). */
    private double porcentajeOcupacion;
    private String aeropuertoMasCritico;
    @Builder.Default
    private List<String> topAeropuertos = new ArrayList<>();
}
