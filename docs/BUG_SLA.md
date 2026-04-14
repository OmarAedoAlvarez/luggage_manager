# Bug: SLA ~44-50% cuando debería ser >85%

## Contexto

Backend Spring Boot + frontend React. Simulación de envíos de equipaje entre aeropuertos.

- 72 envíos, origen OJAI (Amán, Asia), destinos múltiples aeropuertos
- Simulación 3 días, fecha inicio `2026-01-02`
- Resultado constante: ~35-40 RETRASADO, ~32-36 ENTREGADO (~44-50% SLA)

---

## Arquitectura clave

| Clase | Descripción |
|---|---|
| `Vuelo` | Solo tiene `LocalTime horaSalida/horaLlegada` — horario diario repetible, **sin fecha** |
| `Envio` | `LocalDateTime fechaHoraIngreso`, `int sla` (1 = mismo continente, 2 = diferente) |
| `PlanDeViaje` | Lista de `Escala` con `LocalDateTime horaSalidaEst` y `horaLlegadaEst` |
| `avanzarDia()` | Llamado por el frontend **una vez por día simulado** |

---

## Flujo actual de `avanzarDia()`

```java
// Estado: fechaSimulada = 2026-01-02, diaActual = 1

// 1. Procesa el día ACTUAL
processDepartures();    // carga maletas en vuelos cuyo horaSalidaEst.toLocalDate() == today
processArrivals();      // descarga maletas cuyo horaLlegadaEst.toLocalDate() == today
processDeliveries();    // marca ENTREGADO si maleta llegó a aeropuertoDestino
checkSlaViolations();   // marca RETRASADO si fechaSimulada.isAfter(ingreso + sla días)
cancelRandomFlightsAndReplan(); // cancela ~5-8% de vuelos planificados hoy y replaniifca

// 2. Avanza al siguiente día
if (diaActual >= diasSimulacion) { finalizar(); return; }
diaActual++;
fechaSimulada = fechaSimulada.plusDays(1);
```

**Secuencia de 3 días:**
- Llamada 1: procesa `2026-01-02` → avanza a `diaActual=2, fechaSimulada=2026-01-03`
- Llamada 2: procesa `2026-01-03` → avanza a `diaActual=3, fechaSimulada=2026-01-04`
- Llamada 3: procesa `2026-01-04` → `diaActual(3) >= diasSimulacion(3)` → finaliza

---

## Generación de planes (`RouteCandidate.toPlan`)

```java
for (int i = 0; i < legs.size(); i++) {
    Leg leg = legs.get(i);
    escalas.add(Escala.builder()
        .horaSalidaEst(leg.departure())   // LocalDateTime calculado por nextDateTimeForFlight
        .horaLlegadaEst(leg.arrival())
        .codigoVuelo(leg.flight().getCodigoVuelo())
        .build());
}
```

`nextDateTimeForFlight(fechaHoraIngreso, horaSalida)` devuelve la siguiente ocurrencia de
`horaSalida` a partir de `fechaHoraIngreso`.

---

## Ejemplo del bug

Envío `000000003`:
```
000000003-20260102-04-03-OERK-003-0003163
# ingreso: 2026-01-02 04:03, destino: OERK, maletas: 3, SLA: 1 día (Asia→Asia)
```

Vuelo disponible: `OJAI→OERK 08:23→10:03`

Traza esperada:
- `leg.departure() = 2026-01-02 08:23` → `horaSalidaEst = 2026-01-02`
- `processDepartures` con `today = 2026-01-02` → carga maleta ✓
- `processArrivals` con `today = 2026-01-02` → descarga en OERK ✓
- `processDeliveries` → maleta en destino → ENTREGADO ✓
- `checkSlaViolations`: `2026-01-02.isAfter(2026-01-03 04:03)` → NO → no marca RETRASADO ✓

**Resultado real: RETRASADO. ¿Por qué?**

Otros envíos en el mismo estado:
```
000000004-20260102-05-25-VIDP-003-0020245   # Asia→Asia, SLA=1d, RETRASADO
000000008-20260102-12-24-UMMS-001-0003833   # Asia→Asia, SLA=2d, RETRASADO
000000006-20260102-09-48-OPKC-003-0004776   # con escala via OERK, RETRASADO
```

---

## Hipótesis principales

### 1. Maletas atrapadas en `EN_VUELO` tras cancelación

`cancelRandomFlightsAndReplan()` corre **después** de `processArrivals`. Cuando cancela un vuelo
que ya "salió" (sus maletas están en `EN_VUELO`):

```java
List<Maleta> affected = maletas.stream()
    .filter(m -> m.getEstado() == EstadoMaleta.EN_VUELO)
    .filter(m -> vuelo.getCodigoVuelo().equals(maletaVueloActual.get(m.getIdMaleta())))
    .toList();
```

Pero en el flujo actual, al finalizar `processArrivals`, **todas las maletas ya pasaron de
`EN_VUELO` a `EN_ALMACEN`** en el destino intermedio. Por tanto `affected` **siempre está vacío**
→ `replanificar` no hace nada → las maletas quedan en el hub sin plan para el día siguiente.

### 2. Vuelos cancelados bloquean `processArrivals` el día siguiente

Si por alguna razón una maleta sí queda en `EN_VUELO` y al día siguiente se ejecuta `processArrivals`:

```java
Vuelo vuelo = vueloByCode.get(escala.getCodigoVuelo());
if (vuelo == null || vuelo.isCancelado()) continue;  // ← se salta
```

La maleta queda permanentemente en `EN_VUELO` sin llegar nunca → RETRASADO al final.

### 3. `checkSlaViolations` marca RETRASADO antes de que el vuelo del día llegue

Orden actual: `processDeliveries` → `checkSlaViolations`. Un vuelo que sale a las 08:23 y llega
a las 10:03 del `2026-01-02` (SLA deadline = final del `2026-01-03`): si el delivery funciona,
debería estar ENTREGADO antes de llegar a checkSla. Pero si `processDeliveries` no lo marca
(bug en cadena), `checkSlaViolations` lo marca RETRASADO ese mismo día innecesariamente para
envíos con SLA=1 cuando `fechaSimulada=2026-01-02` y `deadline=2026-01-03`.

---

## Archivos relevantes

```
backend/src/main/java/com/tasf/backend/simulation/SimulationEngine.java
  - avanzarDia()
  - processDepartures()
  - processArrivals()
  - processDeliveries()
  - checkSlaViolations()
  - cancelRandomFlightsAndReplan()
  - replanificar()

backend/src/main/java/com/tasf/backend/algorithm/RouteCandidate.java
  - toPlan()

backend/src/main/java/com/tasf/backend/algorithm/RoutePlannerSupport.java
  - generateRoutes()
  - nextDateTimeForFlight()
  - isWithinSla()
```

---

## Objetivo

Trazar el ciclo de vida completo de una maleta que termina RETRASADO cuando debería ser ENTREGADO,
identificar en qué paso se rompe la cadena, y corregirlo.

**Meta: SLA > 85% con el dataset de 72 envíos en 3 días.**
