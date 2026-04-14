package com.tasf.backend.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cancelacion {
    private String id;
    private String codigoVuelo;
    private LocalDate fecha;
    private LocalTime hora;
    private String motivo;
}
