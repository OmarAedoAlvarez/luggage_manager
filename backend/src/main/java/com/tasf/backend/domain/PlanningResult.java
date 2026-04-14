package com.tasf.backend.domain;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanningResult {
    private List<PlanDeViaje> planes;
    private MetricaAlgoritmo metrica;
    private List<String> enviosSinRuta;
}
