# ParkFlow API local setup

Requisitos: Java 21, Node.js 20.9 o posterior, pnpm 10 y PostgreSQL 16.

## Base de datos

Crea la base `parkflow`, un rol propietario/migrador (`parkflow_migrator`) y el rol de ejecución `parkflow_app`. El migrador aplica DDL; `parkflow_app` solo necesita conexión y permisos de lectura/escritura sobre tablas y secuencias. Copia `.env.example` a `.env` y completa ambas credenciales. No subas `.env` al repositorio.

Ejemplo para una instalación nueva, ejecutado como administrador PostgreSQL; `\password` solicita la contraseña sin guardarla en el historial:

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

La API ejecuta las migraciones Flyway al iniciar con `DATABASE_MIGRATION_USERNAME`/`DATABASE_MIGRATION_PASSWORD`, y usa `DATABASE_USERNAME`/`DATABASE_PASSWORD` para el acceso de runtime. Si no se configura un migrador distinto, Flyway usa el mismo rol de datasource. La migración inicial inserta roles ADMIN/WORKER, tipos CAR/MOTORCYCLE/VAN y una sede MAIN; no crea usuarios ni contraseñas por defecto.

## Arranque

```sh
cd apps/api
cp .env.example .env
set -a && source .env && set +a
./mvnw spring-boot:run
```

En otra terminal:

```sh
cd apps/frontend
cp .env.example .env.local
pnpm install
pnpm dev
```

Abre http://localhost:3000. La cuenta administradora inicial se crea desde una terminal interactiva (la contraseña no se muestra ni se pasa como argumento):

```sh
cd apps/api
./mvnw spring-boot:run -Dspring-boot.run.arguments=--create-admin
```

Configura capacidad, tarifas, tolerancia e incremento de cobro desde la aplicación. El incremento se configura por tipo de vehículo, entre 1 y 1440 minutos.

## Verificación

```sh
./mvnw -B verify
```

La suite usa Testcontainers y Docker para crear PostgreSQL 16 desechable. Si Docker no está disponible, puede apuntarse a una base vacía y desechable con `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` y `TEST_DATABASE_PASSWORD`; no uses una base con datos operativos.

`API_INTERNAL_URL` es la dirección interna desde la que Next.js llega a Spring Boot. En producción configura el endpoint interno apropiado, no una URL pública innecesaria.
