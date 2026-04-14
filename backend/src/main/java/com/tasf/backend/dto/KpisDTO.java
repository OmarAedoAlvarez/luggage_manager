package com.tasf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpisDTO {
    private int maletasEnTransito;
    private int maletasEntregadas;
    private double cumplimientoSLA;
    private int vuelosActivos;
    private int slaVencidos;
    private double ocupacionPromedioAlmacen;
}
