package com.tasf.backend.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Maleta {
    private String idMaleta;
    private String idEnvio;
    private String ubicacionActual;
    private EstadoMaleta estado;
}
