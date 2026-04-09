# Deployment

Hermes is designed to be simple to deploy. It is a single process with no 
external dependencies beyond a filesystem. The database is just files on disk 
— no database server, no write-ahead log, no coordination.

## Docker

```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY hermes.jar /app/hermes.jar
ENTRYPOINT ["java", "-jar", "/app/hermes.jar"]
CMD ["--db", "/data/snomed.db", "serve"]
```

```shell
docker run -v /path/to/snomed.db:/data/snomed.db:ro -p 8080:8080 hermes
```

Note the `:ro` — the database volume can be mounted read-only.

Use `--bind-address 0.0.0.0` when running in a container to accept connections
from outside:

```shell
docker run -v /path/to/snomed.db:/data/snomed.db:ro -p 8080:8080 hermes \
  --db /data/snomed.db --bind-address 0.0.0.0 serve
```

See [hermes-docker](https://github.com/wardle/hermes-docker) for example 
configurations.

## Horizontal scaling

Multiple hermes instances can share the same database directory. LMDB uses 
memory-mapped files — the operating system's page cache is shared across 
processes, so additional instances add minimal memory overhead.

```shell
hermes --db snomed.db --port 8080 serve &
hermes --db snomed.db --port 8081 serve &
hermes --db snomed.db --port 8082 serve &
```

Put a load balancer (nginx, HAProxy, an API gateway) in front and you have
a horizontally scaled deployment. Each instance is stateless and disposable.

## Updating

When a new SNOMED CT release is available, update in place:

```shell
hermes --progress --db snomed.db \
  install --dist uk.nhs/sct-monolith --api-key trud-api-key.txt --cache-dir /tmp/trud \
  index
```

This downloads the latest release, adds the new data and rebuilds the indices.

For production deployments, you may prefer to build a new ephemeral service and 
switch traffic at the load balancer:

```shell
hermes --progress --db snomed-2024-07.db \
  install --dist uk.nhs/sct-monolith --api-key trud-api-key.txt --cache-dir /tmp/trud \
  index compact
```

Deploy alongside the existing version, switch, roll back if needed.

## Automation

Hermes is straightforward to automate. The project's own GitHub Actions 
workflows download a distribution, build a database and run the full test suite 
— all in a single pipeline:

- [Test-ar-live](https://github.com/wardle/hermes/actions/workflows/test-live-ar.yml) — Argentina edition via MLDS
- [Test-intl-live](https://github.com/wardle/hermes/actions/workflows/test-live-intl.yml) — International edition via MLDS
- [Test-uk-live](https://github.com/wardle/hermes/actions/workflows/test-live-uk.yml) — UK edition via TRUD

## Database compatibility

Hermes uses versions of form `major.minor.commit`. A database created by one 
`major.minor` version can be read by any other version with the same 
`major.minor`. For example, a database from `v1.4.1265` works with `v1.4.1320`,
but not with `v1.3.1262`.

When backwards compatibility would lead to confusing or inconsistent behaviour 
(e.g. a search index format change that would cause different results depending 
on which database was used), the minor version is bumped even for small changes.

## CORS

Configure cross-origin access for browser-based clients:

```shell
hermes --db snomed.db serve --allowed-origins "*"
hermes --db snomed.db serve --allowed-origins "app.example.com,admin.example.com"
hermes --db snomed.db serve --allowed-origin app.example.com --allowed-origin admin.example.com
```

## FHIR

For HL7 FHIR R4 terminology operations ($expand, $validate-code, $lookup, 
$subsumes), use [hades](https://github.com/wardle/hades) which provides a FHIR 
facade over the same hermes database.
