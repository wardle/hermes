# Installation

## Install hermes

There are three ways to install hermes.

### Homebrew (macOS/Linux)

```shell
brew install wardle/tools/hermes
hermes --help
```

This installs `hermes` on your PATH and handles the Java dependency automatically.

### Download a release jar

Download the latest release from [GitHub releases](https://github.com/wardle/hermes/releases/latest).

```shell
java -jar hermes.jar --help
```

### Run from source

Install [Clojure](https://clojure.org/guides/install), then:

```shell
git clone https://github.com/wardle/hermes
cd hermes
clj -M:run --help
```

Running from source is required for [OWL reasoning](owl-reasoning.md) support,
which is not included in the release jar.

> In all documentation, `hermes` is interchangeable with `java -jar hermes.jar`
> or `clj -M:run`.

## Build a database

### 1. Download and import a distribution

You need at least one SNOMED CT distribution. How you obtain this depends on 
your country — see [SNOMED International](https://www.snomed.org/snomed-ct/get-snomed)
and the [Member Licensing and Distribution Centre (MLDS)](https://mlds.ihtsdotools.org/#/landing).

In the United States, the NLM provides the 
[US edition](https://www.nlm.nih.gov/healthit/snomedct/us_edition.html).
In the United Kingdom, distributions are available from 
[TRUD](https://isd.digital.nhs.uk) — the monolith edition (pack 26/subpack 1799)
includes everything, or use the clinical edition (pack 26/subpack 101) and drug 
extension (pack 26/subpack 105) separately.

#### Automated download

Hermes can download distributions directly from the UK 
[TRUD](https://isd.digital.nhs.uk) service or the 
[MLDS](https://mlds.ihtsdotools.org) for many countries worldwide.

See what's available:

```shell
hermes available
```

**UK (via TRUD):**

| Distribution | Description |
|---|---|
| `uk.nhs/sct-clinical` | UK clinical edition (includes International release) |
| `uk.nhs/sct-drug-ext` | UK drug extension (includes dm+d) |
| `uk.nhs/sct-monolith` | UK monolith edition (includes everything) |

```shell
hermes --progress --db snomed.db \
  install --dist uk.nhs/sct-monolith \
  --api-key trud-api-key.txt --cache-dir /tmp/trud
```

You need a [TRUD API key](https://isd.digital.nhs.uk/trud3/user/guest/group/0/home).
The `--api-key` flag takes a path to a file containing the key.

**MLDS (other countries):**

```shell
hermes --db snomed.db install --dist ie.mlds/285520 --username xxxx --password password.txt
```

The `--password` flag takes a path to a file containing the password, not the 
password itself. This is safer for automated pipelines.

To see what options a distribution needs:

```shell
hermes install --dist uk.nhs/sct-clinical --help
```

**Specific version:**

```shell
hermes --db snomed.db install --dist uk.nhs/sct-clinical \
  --api-key trud-api-key.txt --cache-dir /tmp/trud --release-date 2024-07-24
```

This is useful for building reproducible container images.

#### Manual import

Download your distribution from your national release centre, unzip, and import:

```shell
hermes --db snomed.db import ~/Downloads/snomed-2024/
```

You can import multiple distributions into the same database by running import
more than once, or pointing at a directory containing multiple distributions.

### 2. Index

Indexing builds the Lucene search indices and is required for search, ECL and 
reference set operations.

```shell
hermes --db snomed.db index
```

### 3. Compact (optional but recommended)

Compaction reclaims space in the LMDB store.

```shell
hermes --db snomed.db compact
```

### All-in-one

You can chain commands:

```shell
hermes --progress --db snomed.db \
  install --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud \
  index compact serve
```

This will download, import, index, compact and then start a server.

## Check your database

```shell
hermes --db snomed.db status --format json
```

Example output:

```json
{
  "releases": [
    "SNOMED Clinical Terms version: 20240731 [R] (July 2024 Release)",
    "37.2.0_20240410000001 UK clinical extension",
    "37.2.0_20240410000001 UK drug extension"
  ],
  "locales": ["en-GB", "en-US"],
  "components": {
    "concepts": 1068735,
    "descriptions": 3050621,
    "relationships": 7956235,
    "refsets": 541,
    "refset-items": 13349472
  }
}
```

Use `--modules` or `--refsets` for additional detail.

## Localisation

SNOMED CT has region-specific language reference sets that determine the 
preferred synonym for each concept in a given locale. When you build a database,
the search index caches preferred synonyms for the installed locales.

The locale is used for display only — the core API relating to concepts and 
their meaning is not affected by locale.

At runtime, clients specify their locale preference via the `Accept-Language`
HTTP header or the `--locale` startup flag sets the default.

## Platform support

**Apple Silicon:** Works out of the box — native binaries are included.

**FreeBSD and other platforms:** Install lmdb yourself and point hermes at it:

```shell
java -Dlmdbjava.native.lib=/usr/local/lib/liblmdb.so -jar hermes.jar --db snomed.db status
```
