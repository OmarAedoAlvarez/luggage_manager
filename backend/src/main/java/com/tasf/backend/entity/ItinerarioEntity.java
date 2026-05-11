package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "itinerarios",
    indexes = {
        @Index(name = "idx_itin_envio_activo", columnList = "id_pedido, es_activo, version")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItinerarioEntity {

    /** Generado por RouteCandidate.toPlan(): "EBCI-000000001-v1" */
    @Id
    @Column(name = "id_itinerario", length = 120, nullable = false)
    private String idItinerario;

    @Column(name = "id_pedido", length = 50, nullable = false)
    private String idPedido;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "es_activo", nullable = false)
    private boolean esActivo;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;
}
