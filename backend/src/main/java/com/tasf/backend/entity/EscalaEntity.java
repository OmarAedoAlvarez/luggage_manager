package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "escalas_itinerario",
    indexes = {
        @Index(name = "idx_escala_salida", columnList = "hora_salida_est"),
        @Index(name = "idx_escala_vuelo",  columnList = "codigo_vuelo")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(EscalaEntityId.class)
public class EscalaEntity {

    @Id
    @Column(name = "id_itinerario", length = 120, nullable = false)
    private String idItinerario;

    @Id
    @Column(name = "orden", nullable = false)
    private int orden;

    @Column(name = "codigo_vuelo", length = 20, nullable = false)
    private String codigoVuelo;

    /** Aeropuerto de llegada del tramo (destino del vuelo en esta escala) */
    @Column(name = "iata_escala", length = 4, nullable = false)
    private String iataEscala;

    @Column(name = "hora_salida_est", nullable = false)
    private LocalDateTime horaSalidaEst;

    @Column(name = "hora_llegada_est", nullable = false)
    private LocalDateTime horaLlegadaEst;

    @Column(name = "completada", nullable = false)
    private boolean completada;
}
