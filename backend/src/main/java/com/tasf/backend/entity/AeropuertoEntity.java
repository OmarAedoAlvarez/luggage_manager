package com.tasf.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "aeropuertos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AeropuertoEntity implements Persistable<String> {

    @Id
    @Column(name = "codigo_iata", length = 4, nullable = false)
    private String codigoIata;

    @Column(name = "ciudad", length = 80, nullable = false)
    private String ciudad;

    @Column(name = "pais", length = 80, nullable = false)
    private String pais;

    @Column(name = "continente", nullable = false)
    private String continente;

    @Column(name = "huso", nullable = false)
    private int huso;

    @Column(name = "capacidad_almacen", nullable = false)
    private int capacidadAlmacen;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return codigoIata;
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
