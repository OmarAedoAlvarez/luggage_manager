package com.tasf.backend.domain;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDeViaje {
    private String idPlan;
    private String idEnvio;
    private int version;
    private boolean esActivo;
    private List<Escala> escalas;
    private LocalDateTime fechaCreacion;
}
