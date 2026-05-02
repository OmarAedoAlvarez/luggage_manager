package com.tasf.backend.service;

import com.tasf.backend.domain.MetricaAlgoritmo;
import com.tasf.backend.domain.ParametrosSimulacion;
import com.tasf.backend.dto.EnvioDTO;
import com.tasf.backend.dto.SimulationStateDTO;
import com.tasf.backend.dto.ThroughputDiaDTO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExperimentacionService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentacionService.class);
    private static final Path CSV_PATH = Paths.get("experimentos", "experimentos.csv");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String HEADER =
        "experimento_id,fecha_hora,algoritmo,dias_simulacion,tiempo_planificacion_ms," +
        "rutas_evaluadas,fitness,envios_totales,envios_entregados,envios_sla_ok," +
        "pct_sla_cumplido,sla_violados,envios_no_planificados,ocupacion_promedio_almacen\n";

    public synchronized void registrarExperimento(
        SimulationStateDTO finalState,
        ParametrosSimulacion params,
        MetricaAlgoritmo metrica
    ) {
        try {
            ensureFileExists();

            String algoritmo = resolveAlgoritmoAbrev(params.getAlgoritmo());
            int diasSimulacion = params.getDiasSimulacion();

            long tiempoPlanificacionMs = metrica != null ? metrica.getTiempoEjecucionMs() : 0L;
            int rutasEvaluadas = metrica != null ? metrica.getRutasEvaluadas() : 0;
            int fitness = rutasEvaluadas; // proxy: routes evaluated as fitness proxy

            List<EnvioDTO> envios = finalState.getEnvios();
            int enviosTotales = envios.size();
            int enviosEntregados = (int) envios.stream()
                .filter(e -> "ENTREGADO".equals(e.getEstado()))
                .count();
            int enviosSlaOk = finalState.getThroughputHistorial().stream()
                .mapToInt(ThroughputDiaDTO::getSlaOk)
                .sum();
            double pctSlaCumplido = finalState.getKpis().getCumplimientoSLA();
            int slaViolados = finalState.getKpis().getSlaVencidos();
            int enviosNoPlanificados = (int) envios.stream()
                .filter(e -> "RETRASADO".equals(e.getEstado()))
                .count();
            double ocupacionPromedioAlmacen = finalState.getKpis().getOcupacionPromedioAlmacen();

            String row = String.join(",",
                UUID.randomUUID().toString(),
                Instant.now().atOffset(ZoneOffset.UTC).format(ISO),
                algoritmo,
                String.valueOf(diasSimulacion),
                String.valueOf(tiempoPlanificacionMs),
                String.valueOf(rutasEvaluadas),
                String.valueOf(fitness),
                String.valueOf(enviosTotales),
                String.valueOf(enviosEntregados),
                String.valueOf(enviosSlaOk),
                String.format("%.2f", pctSlaCumplido),
                String.valueOf(slaViolados),
                String.valueOf(enviosNoPlanificados),
                String.format("%.2f", ocupacionPromedioAlmacen)
            ) + "\n";

            Files.writeString(CSV_PATH, row, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            log.info("Experimento registrado: {}", CSV_PATH.toAbsolutePath());
        } catch (IOException ex) {
            log.error("Error registrando experimento en CSV", ex);
            throw new RuntimeException("Error al escribir el archivo de experimentos", ex);
        }
    }

    public Path getCsvPath() {
        return CSV_PATH;
    }

    private void ensureFileExists() throws IOException {
        if (!Files.exists(CSV_PATH)) {
            Files.createDirectories(CSV_PATH.getParent());
            Files.writeString(CSV_PATH, HEADER, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        }
    }

    private String resolveAlgoritmoAbrev(String algoritmo) {
        if (algoritmo == null) return "?";
        String upper = algoritmo.trim().toUpperCase();
        if (upper.contains("ANNEALING") || "SA".equals(upper)) return "SA";
        if (upper.contains("TABU") || "TS".equals(upper)) return "TS";
        return upper;
    }
}
