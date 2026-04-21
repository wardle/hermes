# Hermes : terminology tools, library and microservice

[![Scc Count Badge](https://sloc.xyz/github/wardle/hermes)](https://github.com/wardle/hermes/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hermes?category=cocomo&avg-wage=100000)](https://github.com/wardle/hermes/)
[![Test](https://github.com/wardle/hermes/actions/workflows/test.yml/badge.svg)](https://github.com/wardle/hermes/actions/workflows/test.yml)
[![DOI](https://zenodo.org/badge/293230222.svg)](https://zenodo.org/badge/latestdoi/293230222)
<br/>
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/wardle/hermes)](https://github.com/wardle/hermes/releases/latest)
[![Clojars Project](https://img.shields.io/clojars/v/com.eldrix/hermes.svg)](https://clojars.org/com.eldrix/hermes)
[![cljdoc badge](https://cljdoc.org/badge/com.eldrix/hermes)](https://cljdoc.org/d/com.eldrix/hermes)

Hermes is a SNOMED CT terminology server and command-line tool and library. It imports and indexes a distribution 
in under 5 minutes, with fantastic performance characteristics and has no runtime dependencies beyond a filesystem. No Docker, 
no Elasticsearch, no PostgreSQL.

It provides:

* fast full-text search with autocompletion, locale-aware synonyms and ECL constraints
* full [Expression Constraint Language (ECL)](http://snomed.org/ecl) v2.2 — query the ontology by structure, not just text
* inference and subsumption — "is this concept a type of X?" in microseconds
* cross-mapping to and from ICD-10, Read codes, OPCS and arbitrary reference sets
* SNOMED CT compositional grammar — parse, validate, render and normalize expressions
* OWL reasoning for post-coordinated expression classification, subsumption and normal forms
* a native [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server with 29 tools for AI assistants
* a HTTP/JSON API for language-agnostic integration
* an embeddable JVM library for in-process use from Clojure, Java or any JVM language
* declarative [codelist generation](https://github.com/wardle/codelists) from ECL, ICD-10 and ATC codes
* optional [HL7 FHIR R4 terminology server](https://github.com/wardle/hades) via hades

It is designed to work at every scale — from a developer or analyst's laptop, 
to a single instance serving thousands of concurrent users, to a horizontally 
scaled deployment behind an API gateway. The database requires no external 
services and can be shared across multiple instances via a single mounted volume.
Updating to a new SNOMED release is a single command.

Hermes is widely used in production systems across the World. It is fully open source under the Eclipse Public License v2.0.

## Quickstart

You need Java 17+ installed, or use Homebrew which installs Java automatically.

[![Getting started with hermes](https://img.youtube.com/vi/_w-omAIuc28/maxresdefault.jpg)](https://www.youtube.com/watch?v=_w-omAIuc28)

> **Note:** Throughout this documentation, `hermes` refers to the Homebrew-installed
> command. If you downloaded the jar, use `java -jar hermes.jar` instead.
> If you're running from source, use `clj -M:run`. All three are equivalent.

#### Install

```shell
brew install wardle/tools/hermes
```

Or download the latest jar from [GitHub releases](https://github.com/wardle/hermes/releases/latest), or [run from source](doc/installation.md#run-from-source).

#### Download, import, index and compact

If your distribution supports automated download (UK via [TRUD](https://isd.digital.nhs.uk),
or any [MLDS](https://mlds.ihtsdotools.org) member nation):

```shell
hermes --progress --db snomed.db install --dist uk.nhs/sct-monolith --api-key trud-api-key.txt --cache-dir /tmp/trud index compact
```

Or import a manually downloaded distribution:

```shell
hermes --db snomed.db import ~/Downloads/snomed-2024/ index compact
```

#### Run a HTTP server

```shell
hermes --db snomed.db --port 8080 serve
```

```shell
curl -s -H 'Accept-Language: en-GB' \
  'http://localhost:8080/v1/snomed/search?s=heart+attack&constraint=<64572001&maxHits=5' | jq .
```

#### Run an MCP server for AI assistants

```shell
hermes --db snomed.db mcp
```

Add to Claude Code:

```shell
claude mcp add --transport stdio --scope user hermes -- hermes --db /path/to/snomed.db mcp
```

Or add to Claude Desktop's `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hermes": {
      "command": "hermes",
      "args": ["--db", "/path/to/snomed.db", "mcp"]
    }
  }
}
```

See [MCP documentation](doc/mcp.md) for details on the 29 available tools, 
guided prompts and built-in resources.

## Performance

Measured on a MacBook Pro M1 (2021), single process, no external services.

| Operation | In-process | HTTP (single) | HTTP (50 concurrent) |
|---|---|---|---|
| Concept lookup | 0.82 us | 59 us / 15,546 req/s | 311 us / 86,167 req/s |
| Free-text search (10 hits) | 141-184 us | 292-378 us / 2,642-3,312 req/s | 0.93 ms / 27,392 req/s |
| Subsumption test | 13-69 us | 81 us / 11,801 req/s | — |

See [benchmarks](doc/performance.md) for full details.

## Documentation

| Guide | Description |
|---|---|
| [Installation](doc/installation.md) | Install methods, downloading distributions, building a database |
| [HTTP API](doc/http-api.md) | Endpoint reference with examples |
| [MCP server](doc/mcp.md) | AI integration — tools, prompts, resources and configuration |
| [Search and ECL](doc/search-and-ecl.md) | Full-text search, autocompletion and Expression Constraint Language |
| [ECL implementation status](doc/ecl.md) | Feature-by-feature ECL v2.2 (and v2.3) coverage — what works, what's planned, what isn't currently supported |
| [Cross-mapping](doc/cross-mapping.md) | Mapping to/from ICD-10, OPCS, reference sets and analytics |
| [OWL reasoning](doc/owl-reasoning.md) | Post-coordinated expression classification and normal forms |
| [REPL exploration](doc/repl.md) | Interactive SNOMED CT exploration with the Clojure REPL |
| [Library usage](doc/library.md) | Embedding hermes in JVM applications |
| [Deployment](doc/deployment.md) | Containers, horizontal scaling and version management |
| [Performance](doc/performance.md) | Benchmarks and load testing |
| [Development](doc/development.md) | Building, testing, linting and releasing |

## Use cases

I have embedded hermes into clinical systems, where it drives fast 
autocompletion — users start typing and the diagnosis, procedure, occupation or 
ethnicity pops up. They don't generally know they're using SNOMED CT. I use it 
to populate drop-down controls and for real-time decision support — e.g. does 
this patient have a type of motor neurone disease? — switching UI functionality 
on and off based on subsumption checks that return in microseconds.

Hermes is equally at home in data pipelines and large-scale population analytics. 
Because it has no external dependencies and runs read-only from a single 
database file, it can be embedded directly into batch or streaming jobs — an 
Apache Spark executor, a Kafka Streams processor, or a plain ETL script — 
without standing up a separate service. Every worker gets its own 
memory-mapped view of the same file; there is no shared server to become a 
bottleneck. I routinely use hermes to classify clinical records 
against SNOMED hierarchies, cross-map to ICD-10 for reporting, and 
build analytic cohorts from ECL expressions. A large number of my academic 
publications are a direct result of using SNOMED in this way.

For AI and LLM workflows, hermes runs as a local 
[MCP server](doc/mcp.md), giving models direct access to the full SNOMED 
ontology — search, subsumption, cross-mapping and ECL — without network 
round-trips or API keys.

## Related projects

| Project | Description |
|---|---|
| [hades](https://github.com/wardle/hades) | HL7 FHIR R4 terminology server built on hermes |
| [codelists](https://github.com/wardle/codelists) | Declarative codelist generation from ECL, ICD-10 and ATC codes |
| [dmd](https://github.com/wardle/dmd) | UK dictionary of medicines and devices |
| [trud](https://github.com/wardle/trud) | UK reference data updates |
| [deprivare](https://github.com/wardle/deprivare) | Socioeconomic deprivation data |
| [clods](https://github.com/wardle/clods) | UK organisational data |
| [nhspd](https://github.com/wardle/nhspd) | UK geographical data via the NHS postcode directory |

## Support

Raise an issue on [GitHub](https://github.com/wardle/hermes/issues), or more 
formal support options are available on request, including a fully-managed service.

## Licence

Copyright (c) 2020-2026 Mark Wardle / Eldrix Ltd.

Distributed under the [Eclipse Public License 2.0](LICENSE).

Hermes is listed on the [SNOMED International Terminology Services](https://www.implementation.snomed.org/terminology-services) page.

SNOMED CT data requires a separate licence — see [SNOMED International](https://www.snomed.org/snomed-ct/get-snomed) 
or your national release centre.

*Mark*
