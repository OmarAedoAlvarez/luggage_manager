package com.tasf.backend.repository;

import com.tasf.backend.entity.ItinerarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItinerarioRepository extends JpaRepository<ItinerarioEntity, String> {

    /** Recupera el plan activo de un envío. */
    List<ItinerarioEntity> findByIdPedidoAndEsActivo(String idPedido, boolean esActivo);

    /** Para versioning: todos los planes de un envío ordenados por versión desc. */
    List<ItinerarioEntity> findByIdPedidoOrderByVersionDesc(String idPedido);
}
