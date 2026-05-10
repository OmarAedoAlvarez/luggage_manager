package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "log_operaciones",
    indexes = {
        @Index(name = "idx_log_pedido", columnList = "id_pedido"),
        @Index(name = "idx_log_fecha",  columnList = "fecha_hora")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogOperacionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_log")
    private Long idLog;

    @Column(name = "id_pedido", length = 50)
    private String idPedido;

    @Column(name = "tipo_evento", length = 50, nullable = false)
    private String tipoEvento;

    @Column(name = "descripcion", columnDefinition = "TEXT", nullable = false)
    private String descripcion;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    /** VERDE, AMBAR, ROJO — puede ser null */
    @Column(name = "color_semaforo", length = 10)
    private String colorSemaforo;
}
