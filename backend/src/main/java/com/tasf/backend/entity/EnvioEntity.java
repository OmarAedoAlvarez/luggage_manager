package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "envios",
    indexes = {
        @Index(name = "idx_envio_estado",           columnList = "estado"),
        @Index(name = "idx_envio_origen_destino",   columnList = "iata_origen, iata_destino"),
        @Index(name = "idx_envio_fecha",             columnList = "fecha_hora_ingreso")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvioEntity {

    /** PK auto-generada en la BD. No es el número del .txt */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * ID de dominio construido por BaggageParser: "EBCI-000000001"
     * Guardado como referencia; no es la PK.
     */
    @Column(name = "id_pedido", length = 50, nullable = false)
    private String idPedido;

    @Column(name = "id_cliente", length = 50)
    private String idCliente;

    @Column(name = "codigo_aerolinea", length = 10)
    private String codigoAerolinea;

    @Column(name = "iata_origen", length = 4, nullable = false)
    private String iataOrigen;

    @Column(name = "iata_destino", length = 4, nullable = false)
    private String iataDestino;

    @Column(name = "cantidad_maletas", nullable = false)
    private int cantidadMaletas;

    @Column(name = "fecha_hora_ingreso", nullable = false)
    private LocalDateTime fechaHoraIngreso;

    /**
     * SLA en días: 1 = mismo continente, 2 = intercontinental.
     * Calculado por BaggageParser.resolveSla().
     */
    @Column(name = "sla", nullable = false)
    private int sla;

    @Column(name = "estado", length = 20, nullable = false)
    @Builder.Default
    private String estado = "PENDIENTE";
}
