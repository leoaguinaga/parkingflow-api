# ParkFlow API

API REST de ParkFlow: Spring Boot 4 (Java 21), Spring Security con sesión por cookie, JPA y migraciones Flyway sobre PostgreSQL 16.

## Requisitos

| Herramienta | Versión | Notas |
| --- | --- | --- |
| Java (JDK) | 21 | `java -version` |
| PostgreSQL | 16 | Local o con Docker (opción A) |
| Docker | cualquiera reciente | Opcional para la base; necesario para los tests con Testcontainers |

Maven no hace falta instalarlo: se usa el wrapper `./mvnw`.

## Instalación

### 1. Clonar y entrar al módulo

```sh
cd apps/api
```

### 2. Crear la base de datos

Elige **una** de las dos opciones.

#### Opción A: Docker (recomendada)

Desde la raíz del repositorio, crea un `.env` con la contraseña de la base y levanta PostgreSQL:

```sh
cp .env.example .env        # edita DATABASE_PASSWORD
docker compose up -d postgres
```

Esto crea la base `parkflow` con el rol `parkflow_app` (propietario de la base, por lo que puede aplicar migraciones). Espera a que el contenedor esté `healthy` (`docker compose ps`).

#### Opción B: PostgreSQL instalado localmente

Crea la base `parkflow`, un rol propietario/migrador (`parkflow_migrator`) y el rol de ejecución `parkflow_app`. El migrador aplica DDL; `parkflow_app` solo necesita conexión y permisos de lectura/escritura sobre tablas y secuencias.

Como administrador PostgreSQL (`psql`); `\password` solicita la contraseña sin guardarla en el historial:

```sql
CREATE ROLE parkflow_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE parkflow_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
```

```text
\password parkflow_migrator
\password parkflow_app
CREATE DATABASE parkflow OWNER parkflow_migrator;
GRANT CONNECT ON DATABASE parkflow TO parkflow_app;
\connect parkflow
GRANT USAGE ON SCHEMA public TO parkflow_app;
```

### 3. Configurar variables de entorno

```sh
cp .env.example .env
```

| Variable | Descripción | Por defecto |
| --- | --- | --- |
| `DATABASE_URL` | URL JDBC | `jdbc:postgresql://localhost:5432/parkflow` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Rol de ejecución (runtime) | `parkflow_app` / — |
| `DATABASE_MIGRATION_USERNAME` / `DATABASE_MIGRATION_PASSWORD` | Rol que aplica las migraciones Flyway. Si se omiten, se usa el rol de runtime (caso Docker) | — |
| `PORT` | Puerto de la API | `8080` |
| `FRONTEND_ORIGIN` | Origen permitido (CORS) | `http://localhost:3000` |
| `COOKIE_SECURE` | `true` en producción (HTTPS) | `false` |

Completa las contraseñas. Con Docker (opción A), usa la misma `DATABASE_PASSWORD` del `.env` de la raíz y deja vacías las variables `DATABASE_MIGRATION_*`. No subas `.env` al repositorio.

Spring Boot no lee `.env` por sí solo; exporta las variables en tu terminal antes de arrancar:

```sh
set -a && source .env && set +a
```

### 4. Arrancar la API

```sh
./mvnw spring-boot:run
```

Al iniciar, Flyway aplica las migraciones automáticamente. La migración inicial inserta los roles ADMIN/WORKER, los tipos CAR/MOTORCYCLE/VAN y una sede MAIN; **no** crea usuarios ni contraseñas por defecto.

Comprueba que responde:

```sh
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 5. Crear la cuenta administradora

Desde una terminal interactiva (la contraseña no se muestra ni se pasa como argumento):

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments=--create-admin
```

Luego inicia sesión desde el frontend y configura capacidad, tarifas, tolerancia e incremento de cobro desde la aplicación. El incremento se configura por tipo de vehículo, entre 1 y 1440 minutos.

Para levantar el frontend, sigue [`apps/frontend/README.md`](../frontend/README.md).

## Verificación

```sh
./mvnw -B verify
```

La suite usa Testcontainers y Docker para crear un PostgreSQL 16 desechable. Si Docker no está disponible, apunta a una base vacía y desechable con `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` y `TEST_DATABASE_PASSWORD`; no uses una base con datos operativos.

## Docker y despliegue

- `Dockerfile`: build multi-etapa (Maven + JRE 21) que expone el puerto 8080 con healthcheck sobre `/actuator/health`.

  ```sh
  docker build -t parkflow-api .
  ```

- `deploy/`: compose y scripts de despliegue remoto (`compose.yaml`, `remote-deploy.sh`, `FREEBSD.md`). El CI/CD está en `.github/workflows/ci-deploy.yml`.

## Problemas frecuentes

- **`password authentication failed`**: las variables no están exportadas en la terminal actual (repite `set -a && source .env && set +a`) o la contraseña no coincide con la de la base.
- **`permission denied for schema public` al migrar**: el rol de migración no es propietario de la base; revisa el paso 2 (opción B) o deja vacías las variables `DATABASE_MIGRATION_*` para usar el rol de runtime.
- **Puerto 5432 u 8080 ocupado**: detén el servicio que lo usa o cambia `PORT`/el mapeo de puertos.
