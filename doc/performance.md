# Performance

Hermes benefits from the speed of [Apache Lucene](https://lucene.apache.org) 
and [LMDB](https://www.symas.com/lmdb), and from fundamental design decisions 
including read-only operation and memory-mapped data files. It provides a HTTP 
server using the [Jetty web server](https://www.eclipse.org/jetty/).

## Benchmarks

Measured against the SNOMED CT International Edition on a MacBook Pro M1 (2021),
JVM 17, running from source.

### In-process (Clojure API)

Measured with [criterium](https://github.com/hugoduncan/criterium).

| Operation | Mean | Notes |
|---|---|---|
| Concept lookup | 0.82 us | `hermes/concept` by SCTID |
| Bulk lookup (15 concepts) | 14.6 us | Sequential `hermes/concept` x 15 |
| Free-text search | 141-184 us | `hermes/search`, 10 results |
| All children | 230-243 us | `hermes/all-children`, ~121-125 descendants |
| All parents | 19-69 us | `hermes/all-parents`, 10-32 ancestors |
| Subsumption test | 13-69 us | `hermes/subsumed-by?` |

### HTTP API (single client)

Measured with [wrk](https://github.com/wg/wrk).

| Operation | Mean latency | Throughput |
|---|---|---|
| Concept lookup | 59 us | 15,546 req/s |
| Extended concept | 364 us | 2,663 req/s |
| Concept descriptions | 81 us | 11,891 req/s |
| Free-text search (10 results) | 292-378 us | 2,642-3,312 req/s |
| Subsumption test | 81 us | 11,801 req/s |

### HTTP API (concurrent load)

| Operation | Connections | Throughput | p50 latency |
|---|---|---|---|
| Free-text search ("heart attack") | 50 | 27,392 req/s | 0.93 ms |
| Concept lookup | 50 | 86,167 req/s | 311 us |
| Free-text search ("mnd") | 300 | 33,848 req/s | 9.74 ms |

## Load testing

You can reproduce these results with [wrk](https://github.com/wg/wrk):

```shell
# Start the server
hermes --db snomed.db serve

# Search under load
wrk -c300 -t12 -d30s --latency 'http://localhost:8080/v1/snomed/search?s=mnd'

# Concept lookup under load
wrk -c50 -t4 -d10s --latency 'http://localhost:8080/v1/snomed/concepts/24700007'

# Subsumption under load
wrk -c50 -t4 -d10s --latency 'http://localhost:8080/v1/snomed/concepts/24700007/subsumed-by/6118003'
```

## Why is it fast?

- **LMDB** maps the database directly into the process's address space via 
  `mmap`. Concept lookups are pointer dereferences, not queries — no SQL 
  parsing, no network round-trips to a database server.
- **Apache Lucene** provides the same search technology used by Elasticsearch, 
  but embedded — no cluster coordination, no HTTP between search and application.
- **Read-only operation** means no write-ahead logs, no locks, no transaction 
  overhead. Multiple processes can share the same memory-mapped files.
- **Single process** eliminates network latency between services. A concept 
  lookup that takes 59 microseconds over HTTP would take milliseconds if it 
  involved a query from the application to a separate database process.

## Scaling

Given its design, hermes scales easily:

- **Vertically**: A single instance handles thousands of concurrent users.
- **Horizontally**: Run multiple instances on the same read-only database 
  volume. Memory-mapped files are shared by the OS page cache, so each 
  additional instance adds minimal memory overhead.

In real deployments, a single instance has been sufficient for hundreds of 
concurrent users.
