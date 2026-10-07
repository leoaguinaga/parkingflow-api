# Despliegue de la API en un VPS FreeBSD

FreeBSD no ejecuta contenedores Linux, así que aquí **no se usa Docker**: el CI compila el jar, lo copia por SSH y un servicio `rc.d` lo ejecuta con OpenJDK 21. `compose.yaml` y `remote-deploy.sh` solo aplican a un VPS Linux.

> No se ha probado en un FreeBSD real. Los comandos siguen la documentación del sistema; ajusta nombres de paquetes o rutas si tu versión difiere (`pkg search openjdk21`).

Resumen del flujo:

```
GitHub Actions: mvnw verify → mvnw package → scp jar → ssh freebsd-deploy.sh
VPS: current.jar (symlink) → servicio parkflow (java -jar) → Caddy (HTTPS) → Internet
                                         └→ PostgreSQL 16 (127.0.0.1)
```

Los comandos se ejecutan como `root` en el VPS salvo que se indique otra cosa.

## 1. Paquetes

```sh
pkg update
pkg install -y openjdk21-jre postgresql16-server postgresql16-client caddy sudo
```

- `openjdk21-jre`: runtime Java 21 (el jar se compila con Java 21). Queda en `/usr/local/openjdk21`.
- `postgresql16-server` y `postgresql16-client`: base de datos (versión base del proyecto).
- `caddy`: proxy HTTPS con certificado Let's Encrypt automático.
- `sudo`: permite al usuario de despliegue reiniciar solo el servicio.
- `fetch` (health check) y `ssh`/`scp` vienen en el sistema base.

OpenJDK necesita `fdescfs` y `procfs`:

```sh
echo 'fdesc /dev/fd fdescfs rw 0 0' >> /etc/fstab
echo 'proc /proc procfs rw 0 0' >> /etc/fstab
mount -a
```

## 2. PostgreSQL 16

```sh
sysrc postgresql_enable=YES
service postgresql initdb
service postgresql start
```

Genera dos contraseñas largas (`openssl rand -base64 32`) y créalas con `psql`:

```sh
su - postgres -c psql <<'SQL'
CREATE ROLE parkflow_migrator LOGIN PASSWORD 'CAMBIAR_MIGRATOR';
CREATE ROLE parkflow_app LOGIN PASSWORD 'CAMBIAR_APP' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE parkflow OWNER parkflow_migrator;
SQL
```

Flyway corre con `parkflow_migrator` (propietario) y la API con `parkflow_app`; las migraciones V3/V4 ya asignan los permisos. PostgreSQL escucha solo en loopback por defecto (`listen_addresses='localhost'`); no abras el 5432 en el firewall. Verifica que `/var/db/postgres/data16/pg_hba.conf` use `scram-sha-256` para `127.0.0.1/32`.

## 3. Usuarios, directorios y variables

```sh
# Usuario que ejecuta la API (sin shell ni login)
pw useradd parkflow -m -d /var/empty -s /usr/sbin/nologin -c "ParkFlow API"
# Usuario que usa GitHub Actions por SSH
pw useradd deploy -m -s /bin/sh -c "CI deploy"

mkdir -p /usr/local/parkflow/releases
chown -R deploy:parkflow /usr/local/parkflow
chmod 750 /usr/local/parkflow /usr/local/parkflow/releases
touch /var/log/parkflow.log && chown parkflow /var/log/parkflow.log
```

Archivo de entorno `/usr/local/etc/parkflow.env` (secretos fuera de Git):

```sh
install -m 640 -o root -g parkflow /dev/null /usr/local/etc/parkflow.env
cat > /usr/local/etc/parkflow.env <<'EOF'
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/parkflow
DATABASE_USERNAME=parkflow_app
DATABASE_PASSWORD='CAMBIAR_APP'
DATABASE_MIGRATION_USERNAME=parkflow_migrator
DATABASE_MIGRATION_PASSWORD='CAMBIAR_MIGRATOR'
PORT=8080
COOKIE_SECURE=true
EOF
```

El archivo se carga con `. archivo` en `sh`: usa comillas simples en valores con caracteres especiales. Solo root y el grupo `parkflow` lo leen (permisos 640), porque el servicio lo carga ya como usuario `parkflow`.

## 4. Servicio rc.d

`/usr/local/etc/rc.d/parkflow`:

```sh
#!/bin/sh
# PROVIDE: parkflow
# REQUIRE: LOGIN postgresql
# KEYWORD: shutdown

. /etc/rc.subr

name=parkflow
rcvar=parkflow_enable
load_rc_config $name

: ${parkflow_enable:=NO}
: ${parkflow_user:=parkflow}
: ${parkflow_jar:=/usr/local/parkflow/current.jar}
: ${parkflow_env_file:=/usr/local/etc/parkflow.env}
: ${parkflow_java_opts:=-Xmx384m}

pidfile=/var/run/parkflow.pid
child_pidfile=/var/run/parkflow_java.pid
procname=daemon
command=/usr/sbin/daemon
start_cmd=parkflow_start
stop_postcmd="rm -f ${child_pidfile}"

parkflow_start() {
	# -r reinicia si Java muere; -P guarda el pid del supervisor; -u baja privilegios
	/usr/sbin/daemon -r -R 5 -P ${pidfile} -p ${child_pidfile} \
		-u ${parkflow_user} -o /var/log/parkflow.log \
		/bin/sh -c "set -a; . ${parkflow_env_file}; set +a; \
		exec /usr/local/openjdk21/bin/java ${parkflow_java_opts} -jar ${parkflow_jar}"
}

run_rc_command "$1"
```

```sh
chmod 555 /usr/local/etc/rc.d/parkflow
sysrc parkflow_enable=YES
```

Permiso para que `deploy` reinicie solo este servicio:

```sh
cat > /usr/local/etc/sudoers.d/parkflow <<'EOF'
deploy ALL=(root) NOPASSWD: /usr/sbin/service parkflow restart, /usr/sbin/service parkflow start, /usr/sbin/service parkflow status
EOF
chmod 440 /usr/local/etc/sudoers.d/parkflow
visudo -c
```

## 5. HTTPS con Caddy

Necesitas un dominio (registro A hacia la IP del VPS). Edita `/usr/local/etc/caddy/Caddyfile`:

```
api.tudominio.com {
	reverse_proxy 127.0.0.1:8080
}
```

```sh
sysrc caddy_enable=YES
service caddy start
```

## 6. Firewall (pf)

`/etc/pf.conf` (cambia `vtnet0` por tu interfaz; `ifconfig` la muestra):

```
ext_if = "vtnet0"
set skip on lo0
block in all
pass out all keep state
pass in on $ext_if proto tcp to port { 22, 80, 443 }
pass in on $ext_if inet proto icmp
```

```sh
sysrc pf_enable=YES
service pf start
```

Abre antes una segunda sesión SSH para no quedarte fuera. Los puertos 8080 y 5432 no se exponen. Si el proveedor tiene su propio firewall (por ejemplo Oracle Cloud security list), abre también 80 y 443 ahí.

## 7. Llaves SSH para GitHub Actions

En **tu máquina local**:

```sh
ssh-keygen -t ed25519 -N '' -C github-actions-parkflow -f ./parkflow_deploy
```

En el **VPS**, autoriza la llave pública:

```sh
su - deploy -c 'mkdir -p ~/.ssh && chmod 700 ~/.ssh'
cat parkflow_deploy.pub >> /home/deploy/.ssh/authorized_keys   # pega el contenido
chown deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
```

Obtén `known_hosts` (desde tu máquina; verifica que la huella coincida con la del VPS):

```sh
ssh-keyscan -t ed25519 IP_DEL_VPS
```

## 8. Secretos en GitHub (repo `parkingflow-api`)

Settings → Secrets and variables → Actions:

| Secreto | Valor |
|---|---|
| `VPS_HOST` | IP o host del VPS, sin espacios ni saltos de línea |
| `VPS_USER` | `deploy` |
| `VPS_SSH_KEY` | contenido completo de `parkflow_deploy` (privada, con líneas BEGIN/END) |
| `VPS_KNOWN_HOSTS` | salida de `ssh-keyscan` |
| `VPS_DEPLOY_DIR` | `/usr/local/parkflow` |

`GHCR_USERNAME` y `GHCR_READ_TOKEN` ya no hacen falta. Borra la llave privada local cuando la hayas guardado.

## 9. Script de despliegue en el VPS

`/usr/local/parkflow/freebsd-deploy.sh` (lo copia el CI en cada despliegue; es POSIX `sh`, no necesita bash):

```sh
#!/bin/sh
set -eu
tag="${1:?Falta la etiqueta de versión}"
dir=/usr/local/parkflow
new="$dir/releases/api-$tag.jar"
old="$(readlink "$dir/current.jar" 2>/dev/null || true)"

[ -f "$new" ] || { echo "No existe $new" >&2; exit 1; }
ln -sfn "$new" "$dir/current.jar"
sudo -n /usr/sbin/service parkflow restart || sudo -n /usr/sbin/service parkflow start

i=0
while [ "$i" -lt 36 ]; do
	if fetch -q -o - http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
		# conserva las 5 versiones más recientes
		ls -1t "$dir"/releases/api-*.jar | tail -n +6 | xargs rm -f
		echo "$tag" > "$dir/.deployed-tag"
		exit 0
	fi
	i=$((i + 1))
	sleep 5
done

echo "La API no respondió a /actuator/health; se restaura la versión anterior." >&2
if [ -n "$old" ] && [ -f "$old" ]; then
	ln -sfn "$old" "$dir/current.jar"
	sudo -n /usr/sbin/service parkflow restart || true
fi
exit 1
```

## 10. Cambios en el workflow

En `.github/workflows/ci-deploy.yml`, el job `verify` no cambia. Reemplaza los pasos de `publish-and-deploy` (imagen, GHCR y `remote-deploy.sh`) por:

```yaml
  publish-and-deploy:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    needs: verify
    runs-on: ubuntu-latest
    env:
      TAG: ${{ github.sha }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Build jar
        run: ./mvnw -B -DskipTests package
      - name: Prepare VPS SSH access
        env:
          VPS_SSH_KEY: ${{ secrets.VPS_SSH_KEY }}
          VPS_KNOWN_HOSTS: ${{ secrets.VPS_KNOWN_HOSTS }}
        run: |
          install -m 700 -d "$HOME/.ssh"
          printf '%s\n' "$VPS_SSH_KEY" > "$HOME/.ssh/parkflow_vps"
          chmod 600 "$HOME/.ssh/parkflow_vps"
          printf '%s\n' "$VPS_KNOWN_HOSTS" > "$HOME/.ssh/known_hosts"
      - name: Deploy jar on VPS
        env:
          VPS_HOST: ${{ secrets.VPS_HOST }}
          VPS_USER: ${{ secrets.VPS_USER }}
          VPS_DEPLOY_DIR: ${{ secrets.VPS_DEPLOY_DIR }}
        run: |
          : "${VPS_HOST:?Falta el secreto VPS_HOST}" "${VPS_USER:?Falta el secreto VPS_USER}" "${VPS_DEPLOY_DIR:?Falta el secreto VPS_DEPLOY_DIR}"
          SSH_OPTS="-i $HOME/.ssh/parkflow_vps -o BatchMode=yes -o StrictHostKeyChecking=yes"
          scp $SSH_OPTS target/api-0.0.1-SNAPSHOT.jar "$VPS_USER@$VPS_HOST:$VPS_DEPLOY_DIR/releases/api-$TAG.jar"
          scp $SSH_OPTS deploy/freebsd-deploy.sh "$VPS_USER@$VPS_HOST:$VPS_DEPLOY_DIR/freebsd-deploy.sh"
          ssh $SSH_OPTS "$VPS_USER@$VPS_HOST" "chmod 750 '$VPS_DEPLOY_DIR/freebsd-deploy.sh' && '$VPS_DEPLOY_DIR/freebsd-deploy.sh' '$TAG'"
```

Guarda el script de la sección 9 como `deploy/freebsd-deploy.sh` en el repo. En FreeBSD `scp` usa SFTP por defecto; no necesita ajustes.

## 11. Primer arranque y verificación

```sh
service parkflow start
sleep 20
fetch -q -o - http://127.0.0.1:8080/actuator/health      # {"status":"UP"}
fetch -q -o - https://api.tudominio.com/actuator/health
tail -n 50 /var/log/parkflow.log
sockstat -4 -l | grep -E '8080|5432|443'
```

Antes del primer despliegue de CI, `current.jar` no existe; para probar a mano copia un jar a `releases/` y crea el enlace: `ln -s /usr/local/parkflow/releases/api-X.jar /usr/local/parkflow/current.jar`.

## Notas

- **Cookies y frontend en Vercel:** la cookie de sesión es `SameSite=Lax` y `Secure`. Si el navegador llama a la API desde otro sitio (`*.vercel.app` → `api.tudominio.com`), no se enviará la cookie en peticiones cross-site. Lo habitual es que el frontend la consuma mediante un rewrite de Next.js hacia la API o que ambos compartan dominio padre. Confírmalo antes de la demo.
- **Respaldo:** `su - postgres -c 'pg_dump -Fc parkflow' > parkflow-$(date +%F).dump` (sigue la estrategia de `docs/CI-SEGURIDAD-Y-BASE-DE-DATOS.md`).
- **Actualizar Java:** `pkg upgrade openjdk21-jre` y `service parkflow restart`.
