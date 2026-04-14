package com.tasf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThroughputDiaDTO {
    private int dia;
    private int maletasProcesadas;
    private int slaOk;
    private int slaBreach;
}
