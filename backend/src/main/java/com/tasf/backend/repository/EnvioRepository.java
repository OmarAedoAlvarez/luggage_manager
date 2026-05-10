package com.tasf.backend.repository;

import com.tasf.backend.entity.EnvioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EnvioRepository extends JpaRepository<EnvioEntity, Long> {

    /**
     * Consulta envíos dentro de un rango de fechas de ingreso.
     * Usado por SimulationController para cargar solo los envíos del periodo simulado.
     */
    List<EnvioEntity> findByFechaHoraIngresoBetween(LocalDateTime desde, LocalDateTime hasta);

    /**
     * Verifica si ya existe un envío por su id_pedido de dominio.
     * Usado por el seeder y el upload para evitar duplicados.
     */
    boolean existsByIdPedido(String idPedido);

    /**
     * Cuenta envíos por aeropuerto de origen — útil para seeder parcial.
     */
    @Query("SELECT COUNT(e) FROM EnvioEntity e WHERE e.iataOrigen = :iata")
    long countByIataOrigen(@Param("iata") String iata);
}
