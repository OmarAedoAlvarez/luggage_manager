package com.tasf.backend.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricaAlgoritmo {
    private String idMetrica;
    private String algoritmoUsado;
    private long tiempoEjecucionMs;
    private int rutasEvaluadas;
    private LocalDateTime fechaEjecucion;
}
