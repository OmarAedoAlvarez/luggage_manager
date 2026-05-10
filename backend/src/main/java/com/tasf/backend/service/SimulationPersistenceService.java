package com.tasf.backend.service;

import com.tasf.backend.domain.PlanDeViaje;
import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.entity.ItinerarioEntity;
import com.tasf.backend.entity.EscalaEntity;
import com.tasf.backend.entity.MetricaEjecucionEntity;
import com.tasf.backend.entity.LogOperacionEntity;
import com.tasf.backend.entity.EnvioEntity;
import com.tasf.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SimulationPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(SimulationPersistenceService.class);

    private final ItinerarioRepository itinerarioRepository;
    private final MetricaEjecucionRepository metricaRepository;
    private final LogOperacionRepository logRepository;
    private final EnvioRepository envioRepository;
    private final EscalaRepository escalaRepository;

    public SimulationPersistenceService(
            ItinerarioRepository itinerarioRepository,
            MetricaEjecucionRepository metricaRepository,
            LogOperacionRepository logRepository,
            EnvioRepository envioRepository,
            EscalaRepository escalaRepository) {
        this.itinerarioRepository = itinerarioRepository;
        this.metricaRepository = metricaRepository;
        this.logRepository = logRepository;
        this.envioRepository = envioRepository;
        this.escalaRepository = escalaRepository;
    }

    @Transactional
    public void persistSimulationResults(
            List<PlanDeViaje> planes,
            List<MetricaAlgoritmo> metricas,
            List<String> logOperaciones,
            List<com.tasf.backend.domain.Envio> domainEnvios) {
        
        log.info("Persisting simulation results to database...");

        // 1. Persistir Itinerarios y Escalas
        for (PlanDeViaje plan : planes) {
            ItinerarioEntity itinerario = ItinerarioEntity.builder()
                .idItinerario(plan.getIdEnvio() + "-" + plan.getAlgoritmoUsado() + "-v" + plan.getVersion())
                .idPedido(plan.getIdEnvio())
                .version(plan.getVersion())
                .esActivo(true) // Asumimos que el persistido al final es el activo
                .algoritmoUsado(plan.getAlgoritmoUsado())
                .fechaCreacion(LocalDateTime.now())
                .build();
            
            itinerarioRepository.save(itinerario);
            
            // Escalas
            List<EscalaEntity> escalas = new ArrayList<>();
            for (int i = 0; i < plan.getEscalas().size(); i++) {
                var esc = plan.getEscalas().get(i);
                escalas.add(EscalaEntity.builder()
                    .idItinerario(itinerario.getIdItinerario())
                    .orden(i + 1)
                    .codigoVuelo(esc.getCodigoVuelo())
                    .iataEscala(esc.getCodigoAeropuerto())
                    .horaSalidaEst(esc.getHoraSalidaEst())
                    .horaLlegadaEst(esc.getHoraLlegadaEst())
                    .completada(true)
                    .build());
            }
            escalaRepository.saveAll(escalas);
        }

        // 2. Persistir Métricas
        for (MetricaAlgoritmo m : metricas) {
            MetricaEjecucionEntity entity = MetricaEjecucionEntity.builder()
                .idMetrica("MET-" + m.getAlgoritmoUsado() + "-" + System.nanoTime())
                .idItinerario(null) // Opcional vincular
                .algoritmoUsado(m.getAlgoritmoUsado())
                .rutasEvaluadas(m.getRutasEvaluadas())
                .tiempoMs(m.getTiempoEjecucionMs())
                .exito(true)
                .fechaEjecucion(LocalDateTime.now())
                .build();
            metricaRepository.save(entity);
        }

        // 3. Persistir Logs
        List<LogOperacionEntity> logEntities = logOperaciones.stream()
            .map(line -> LogOperacionEntity.builder()
                .tipoEvento("SIMULATION_EVENT")
                .descripcion(line)
                .fechaHora(LocalDateTime.now())
                .build())
            .toList();
        logRepository.saveAll(logEntities);

        // 4. Actualizar estado de los envíos en la DB
        for (com.tasf.backend.domain.Envio de : domainEnvios) {
            // Buscamos el envío en la DB por su idPedido
            // Nota: Esto es costoso en un loop. En producción se haría un batch update.
            // Pero para este volumen (miles) está bien.
            envioRepository.findAll().stream()
                .filter(e -> e.getIdPedido().equals(de.getIdEnvio()))
                .findFirst()
                .ifPresent(entity -> {
                    entity.setEstado(de.getEstado().name());
                    envioRepository.save(entity);
                });
        }

        log.info("Simulation results persisted successfully.");
    }
}
