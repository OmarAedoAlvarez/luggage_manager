package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;
import java.time.LocalTime;

@Entity
@Table(name = "vuelos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VueloEntity implements Persistable<String> {

    /**
     * Generado por FlightParser como "ORIGEN-DESTINO-HH:MM"
     * Ej: "EBCI-SUAA-00:47"
     */
    @Id
    @Column(name = "codigo_vuelo", length = 20, nullable = false)
    private String codigoVuelo;

    @Column(name = "iata_origen", length = 4, nullable = false)
    private String iataOrigen;

    @Column(name = "iata_destino", length = 4, nullable = false)
    private String iataDestino;

    @Column(name = "hora_salida", nullable = false)
    private LocalTime horaSalida;

    @Column(name = "hora_llegada", nullable = false)
    private LocalTime horaLlegada;

    @Column(name = "capacidad_total", nullable = false)
    private int capacidadTotal;

    @Column(name = "tipo", length = 20)
    private String tipo;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return codigoVuelo;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    public void markNotNew() {
        this.isNew = false;
    }
}
