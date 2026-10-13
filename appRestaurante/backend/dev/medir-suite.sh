#!/usr/bin/env bash
# Repite la suite midiendo cada corrida. Si una pasa del umbral, captura el volcado de hilos de la JVM de pruebas y el
# estado de la base (pg_stat_activity, pg_locks) ANTES de que termine, para poder explicar lentitudes como la de F-012.
#
# Uso (con la base de docker-compose arriba):   ./dev/medir-suite.sh [corridas=5] [umbral_segundos=180]
# Resultado: dev/diagnostico/<fecha>/  con un resumen y, si hubo lentitud, los volcados.
set -u
CORRIDAS="${1:-5}"
UMBRAL="${2:-180}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd -W 2>/dev/null || pwd)"
SALIDA="dev/diagnostico/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$SALIDA"
RESUMEN="$SALIDA/resumen.txt"
: > "$RESUMEN"

capturar() {
  local n="$1"
  echo "== corrida $n supero ${UMBRAL}s: capturando diagnostico ==" | tee -a "$RESUMEN"
  docker exec restaurantes-suite sh -c 'for p in $(ls /proc | grep -E "^[0-9]+$"); do
      if tr "\0" " " < /proc/$p/cmdline 2>/dev/null | grep -q "surefire"; then jcmd $p Thread.print; fi; done' \
      > "$SALIDA/hilos-corrida$n.txt" 2>&1
  docker exec restaurantes-dev-postgres-1 psql -U postgres -d restaurantes -c \
      "select pid, state, wait_event_type, wait_event, now() - xact_start as xact, left(query, 120) as query from pg_stat_activity where datname = 'restaurantes' order by xact_start nulls last" \
      > "$SALIDA/pg_stat_activity-corrida$n.txt" 2>&1
  docker exec restaurantes-dev-postgres-1 psql -U postgres -d restaurantes -c \
      "select l.pid, l.locktype, l.mode, l.granted, a.state, left(a.query, 100) as query from pg_locks l join pg_stat_activity a using (pid) where not l.granted or l.locktype in ('advisory') order by l.granted, l.pid" \
      > "$SALIDA/pg_locks-corrida$n.txt" 2>&1
}

for n in $(seq 1 "$CORRIDAS"); do
  inicio=$(date +%s)
  MSYS_NO_PATHCONV=1 docker run --rm --name restaurantes-suite -v "$RAIZ":/work -w /work -v restaurantes-m2:/root/.m2 \
      --network restaurantes-dev -e TEST_DB_URL=jdbc:postgresql://postgres:5432/restaurantes \
      maven:3.9-eclipse-temurin-21 mvn -B test > "$SALIDA/corrida$n.log" 2>&1 &
  pid=$!
  capturado=0
  while kill -0 "$pid" 2>/dev/null; do
    sleep 5
    if [ $(( $(date +%s) - inicio )) -gt "$UMBRAL" ] && [ "$capturado" -eq 0 ]; then
      capturar "$n"
      capturado=1
    fi
  done
  wait "$pid"
  duracion=$(( $(date +%s) - inicio ))
  linea=$(grep -E "Tests run:.*[0-9]$" "$SALIDA/corrida$n.log" | tail -1)
  echo "corrida $n: ${duracion}s  $linea" | tee -a "$RESUMEN"
done
echo "Listo: $RESUMEN"
