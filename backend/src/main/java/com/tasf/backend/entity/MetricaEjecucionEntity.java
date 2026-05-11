package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "metricas_ejecucion",
    indexes = {
        @Index(name = "idx_metrica_fecha", columnList = "fecha_ejecucion")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricaEjecucionEntity {

    /** Generado por los algoritmos: "MET-<nanoTime>" */
    @Id
    @Column(name = "id_metrica", length = 100, nullable = false)
    private String idMetrica;

    @Column(name = "id_itinerario", length = 120)
    private String idItinerario;

    @Column(name = "rutas_evaluadas", nullable = false)
    private int rutasEvaluadas;

    /** tiempoEjecucionMs es un long en Java */
    @Column(name = "tiempo_ms", nullable = false)
    private long tiempoMs;

    @Column(name = "exito", nullable = false)
    private boolean exito;

    @Column(name = "fecha_ejecucion", nullable = false)
    private LocalDateTime fechaEjecucion;
}
