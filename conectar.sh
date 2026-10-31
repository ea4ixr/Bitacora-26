#!/bin/bash
# Bitácora 26 - conecta el servidor con su almacén privado de GitHub.
# Crea una llave solo para GitHub, instala el sincronizador y lo programa cada minuto.
set -e
echo ">> Preparando la conexión con GitHub..."
DEBIAN_FRONTEND=noninteractive apt-get install -y -q git rsync >/dev/null
mkdir -p /root/.ssh /opt/bitacora26
chmod 700 /root/.ssh
K=/root/.ssh/bitacora26_github
[ -f $K ] || ssh-keygen -q -t ed25519 -N "" -C "servidor-bitacora26" -f $K
cat > /usr/local/bin/bitacora26-sync <<'FIN_SYNC'
#!/bin/bash
# Bitácora 26 - sincroniza el servidor con GitHub (ea4ixr/bitacora26-servidor).
# Se ejecuta cada minuto (cron). Si hay cambios en la rama main:
#   - copia servidor/ a /opt/bitacora26 y arranca/actualiza los contenedores;
#   - ejecuta las órdenes nuevas de ordenes/*.sh (una sola vez cada una);
#   - sube el resultado a la rama "estado" para poder leerlo desde GitHub.
set -u
B=/opt/bitacora26
R=$B/repo
E=$B/estado
HECHAS=$B/ordenes-hechas.txt
URL=git@github.com:ea4ixr/bitacora26-servidor.git
export GIT_SSH_COMMAND="ssh -i /root/.ssh/bitacora26_github -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o BatchMode=yes"
GIT_ID=(-c user.name="Servidor Bitacora 26" -c user.email="servidor@bitacora26.es")

exec 9>/run/bitacora26-sync.lock
flock -n 9 || exit 0

FORZAR=0
if [ ! -d "$R/.git" ]; then
  git clone -q "$URL" "$R" || exit 0
  FORZAR=1
fi
if [ ! -d "$E/.git" ]; then
  git clone -q -b estado "$URL" "$E" || exit 0
fi
touch "$HECHAS"

cd "$R" || exit 1
git fetch -q origin main || exit 0
ANT=$(git rev-parse HEAD)
NUEVO=$(git rev-parse origin/main)
[ "$ANT" = "$NUEVO" ] && [ "$FORZAR" = 0 ] && exit 0
git reset -q --hard origin/main

AHORA=$(date -u '+%Y-%m-%d %H:%M:%S')
mkdir -p "$E/resultados"

# 1) Despliegue
{
  echo "=== Despliegue $AHORA UTC - commit $(git rev-parse --short HEAD) ==="
  if [ -d "$R/servidor" ]; then
    CAD_ANT=$(md5sum "$B/Caddyfile" 2>/dev/null)
    rsync -rlc --inplace --exclude web/ "$R/servidor/" "$B/"
    if [ -d "$R/servidor/web" ]; then
      rsync -rlc --delete "$R/servidor/web/" "$B/web/"
    fi
    cd "$B"
    docker compose up -d --remove-orphans --build 2>&1
    if [ "$CAD_ANT" != "$(md5sum "$B/Caddyfile" 2>/dev/null)" ]; then
      echo ">> Caddyfile cambiado: recargando la web"
      docker compose exec -T web caddy reload --config /etc/caddy/Caddyfile 2>&1
    fi
    echo
    docker compose ps 2>&1
  fi
  if [ -f "$R/sistema/bitacora26-sync" ] && ! cmp -s "$R/sistema/bitacora26-sync" /usr/local/bin/bitacora26-sync; then
    install -m 755 "$R/sistema/bitacora26-sync" /usr/local/bin/bitacora26-sync
    echo ">> Script de sincronización actualizado"
  fi
} > "$E/despliegue.txt" 2>&1

# 2) Órdenes nuevas (cada una se ejecuta una sola vez)
cd "$R"
for F in ordenes/*.sh; do
  [ -f "$F" ] || continue
  N=$(basename "$F")
  grep -qxF "$N" "$HECHAS" && continue
  echo "$N" >> "$HECHAS"
  {
    echo "=== Orden $N - $(date -u '+%Y-%m-%d %H:%M:%S') UTC ==="
    cd "$B" && timeout 900 bash "$R/$F" 2>&1
    echo "=== Fin (código $?) ==="
  } > "$E/resultados/${N%.sh}.txt" 2>&1
done

# 3) Subir el estado a GitHub
cd "$E"
git add -A
git "${GIT_ID[@]}" commit -q -m "Estado $AHORA UTC" && git push -q origin estado
exit 0
FIN_SYNC
chmod 755 /usr/local/bin/bitacora26-sync
echo "* * * * * root /usr/local/bin/bitacora26-sync >>/var/log/bitacora26-sync.log 2>&1" > /etc/cron.d/bitacora26-sync
chmod 644 /etc/cron.d/bitacora26-sync
echo
echo "=================================================="
echo " LISTO. Copia la linea de abajo (empieza por ssh-ed25519)"
echo " y pegasela a Claude en el chat:"
echo "=================================================="
cat $K.pub
