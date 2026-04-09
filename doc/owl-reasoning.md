# OWL Reasoning

Hermes provides optional OWL reasoning for post-coordinated expressions using 
the [OWL API](https://github.com/owlcs/owlapi) and the 
[ELK reasoner](https://github.com/liveontologies/elk-reasoner).

OWL reasoning provides:

- **Classification** — determine equivalent concepts, direct super-concepts 
  and proximal primitive supertypes for post-coordinated expressions
- **Subsumption** — more accurate than structural subsumption for complex 
  expressions, correctly handling GCI axioms
- **Necessary Normal Form (NNF)** — a canonical representation with proximal 
  primitive supertypes as focus concepts and all necessary relationships

## Availability

OWL reasoning is opt-in. The OWL libraries are **not included** in the release 
jar or Homebrew install — they add ~30MB and most users don't need them.

| Install method | OWL support |
|---|---|
| Homebrew (`hermes`) | No |
| Release jar (`hermes.jar`) | No |
| From source with `:owl` alias | Yes |

Without OWL, hermes still provides structural subsumption and expression 
validation — which handles the majority of use cases.

## Setup

### 1. Import with OWL axioms

OWL axiom reference set files must be imported. Use the `--owl` flag during 
import:

```shell
clj -M:run:owl --db snomed.db import ~/Downloads/snomed-2024/ --owl
clj -M:run:owl --db snomed.db index compact
```

Or with automated download:

```shell
clj -M:run:owl --progress --db snomed.db \
  install --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud --owl \
  index compact
```

### 2. Run with OWL enabled

```shell
# HTTP server
clj -M:run:owl --db snomed.db --owl serve

# MCP server
clj -M:run:owl --db snomed.db --owl mcp
```

The reasoner initialises at startup. Check its status:

```shell
curl -s 'http://localhost:8080/v1/snomed/status' | jq .reasoningStatus
# => "active", "inactive", or "unavailable"
```

## HTTP endpoints

### Classify expression

```shell
curl -s 'http://localhost:8080/v1/snomed/classify?expression=73211009:363698007=84301002' | jq .
```

Returns:
- `equivalent-concepts` — pre-coordinated concepts equivalent to the expression
- `direct-super-concepts` — immediate supertypes
- `proximal-primitive-supertypes` — nearest primitive ancestors

### Necessary Normal Form

```shell
curl -s 'http://localhost:8080/v1/snomed/necessary-normal-form?expression=73211009:363698007=84301002' | jq .
```

The NNF uses proximal primitive supertypes as focus concepts with all necessary 
relationships — a canonical form useful for comparison and storage.

### Expression subsumption (OWL mode)

```shell
curl -s 'http://localhost:8080/v1/snomed/subsumes?a=73211009&b=73211009:363698007=84301002&mode=owl' | jq .
```

The `mode=owl` parameter uses the ELK reasoner instead of structural comparison.

## Structural vs OWL subsumption

| | Structural (default) | OWL |
|---|---|---|
| Available in | All installs | Source with `:owl` alias only |
| Handles GCI axioms | No | Yes |
| Handles role composition | No | Yes |
| Speed | Microseconds | Milliseconds |
| Sufficient for most use cases | Yes | — |

Structural subsumption normalises both expressions and compares them 
structurally. This works correctly for the vast majority of cases. OWL 
reasoning is needed only when GCI axioms or advanced DL features affect the 
subsumption relationship.

## Library usage

Add the OWL dependencies to your project:

```clojure
;; deps.edn
net.sourceforge.owlapi/owlapi-apibinding {:mvn/version "5.5.1"
                                           :exclusions  [net.sourceforge.owlapi/owlapi-rio
                                                         net.sourceforge.owlapi/owlapi-oboformat
                                                         net.sourceforge.owlapi/owlapi-tools]}
io.github.liveontologies/elk-owlapi      {:mvn/version "0.6.0"}
```

Then:

```clojure
(require '[com.eldrix.hermes.core :as hermes])

(def svc (hermes/open "snomed.db"))
(hermes/activate-reasoner svc)
(hermes/reasoning-status svc)  ;; => :active

;; Classify
(hermes/classify-expression svc "73211009:363698007=84301002")

;; OWL subsumption
(hermes/subsumes svc "73211009" "73211009:363698007=84301002" :mode :owl)

;; Necessary Normal Form
(hermes/necessary-normal-form svc "73211009:363698007=84301002")
```
