#!/bin/bash
# Bitácora 26 - preparación del servidor (bitacora26.es)
# Deja listo: actualizaciones, cortafuegos, protección contra intentos de entrada,
# web con https gratuito (Caddy + Let's Encrypt) y base de datos (PostgreSQL), todo con Docker.
# Se puede ejecutar más de una vez sin problema.
set -e
exec > >(tee -a /var/log/bitacora26-instalar.log) 2>&1
echo "=== Bitácora 26: preparando el servidor ($(date)) ==="

export DEBIAN_FRONTEND=noninteractive
APT="apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"

echo ">> 1/6 Actualizando el sistema..."
apt-get update
$APT upgrade
$APT install ufw fail2ban unattended-upgrades curl openssl

echo ">> 2/6 Actualizaciones de seguridad automáticas..."
dpkg-reconfigure -f noninteractive unattended-upgrades || true

echo ">> 3/6 Cortafuegos (solo SSH, web y web segura)..."
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable
systemctl enable --now fail2ban

echo ">> 4/6 Docker..."
if ! docker compose version >/dev/null 2>&1; then
  $APT install docker.io docker-compose-v2
fi
systemctl enable --now docker

echo ">> 5/6 Web y base de datos..."
B=/opt/bitacora26
mkdir -p $B/web $B/copias
cd $B
if [ ! -f .env ]; then
  echo "POSTGRES_DB=bitacora26" > .env
  echo "POSTGRES_USER=bitacora26" >> .env
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" >> .env
  chmod 600 .env
fi

cat > Caddyfile <<'EOF'
bitacora26.es, www.bitacora26.es {
	encode gzip
	root * /srv
	file_server
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "strict-origin-when-cross-origin"
	}
}
EOF

cat > docker-compose.yml <<'EOF'
services:
  web:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./web:/srv:ro
      - caddy_data:/data
      - caddy_config:/config
  db:
    image: postgres:17
    restart: unless-stopped
    env_file: .env
    volumes:
      - pgdata:/var/lib/postgresql/data
volumes:
  caddy_data:
  caddy_config:
  pgdata:
EOF

if [ ! -f web/index.html ]; then
cat > web/index.html <<'EOF'
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bitácora 26</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#101a2a;color:#e8eef8;font-family:Arial,Helvetica,sans-serif;text-align:center;padding:24px;box-sizing:border-box}
h1{color:#ffbe3c;font-size:44px;margin:0 0 12px}
p{color:#c9d6ea;font-size:18px;line-height:1.5;margin:6px 0}
a{color:#ffbe3c}
</style>
</head>
<body>
<main>
<h1>Bitácora 26</h1>
<p>Libro de guardia gratuito para radioaficionados, Banda Ciudadana y radioclubs.</p>
<p>Muy pronto: directorio de operadores, confirmación de contactos y cluster.</p>
<p><a href="https://ea4ixr.github.io/Bitacora-26/">Descargar Bitácora 26</a></p>
</main>
</body>
</html>
EOF
fi

docker compose pull
docker compose up -d

echo ">> 6/6 Copia diaria de la base de datos (se guardan 14 días)..."
cat > /etc/cron.daily/bitacora26-copia <<'EOF'
#!/bin/bash
cd /opt/bitacora26 || exit 1
F=copias/bitacora26-$(date +%Y%m%d).sql.gz
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$F"
chmod 600 "$F"
find copias -name 'bitacora26-*.sql.gz' -mtime +14 -delete
EOF
chmod 755 /etc/cron.daily/bitacora26-copia

sleep 5
echo
docker compose ps
echo
echo "=================================================="
echo "  TODO LISTO. Servidor de Bitácora 26 preparado."
echo "  En unos minutos: https://bitacora26.es"
echo "=================================================="
