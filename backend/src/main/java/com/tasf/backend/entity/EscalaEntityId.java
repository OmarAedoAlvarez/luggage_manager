package com.tasf.backend.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscalaEntityId implements Serializable {
    private String idItinerario;
    private int orden;
}
