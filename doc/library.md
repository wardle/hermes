# Library Usage

Hermes can be embedded directly into JVM applications as a library. This gives 
you in-process access with sub-microsecond concept lookups and no network 
overhead.

## Dependencies

### Clojure (deps.edn)

Using maven coordinates:

```clojure
com.eldrix/hermes {:mvn/version "RELEASE"}
```

Or git coordinates (pin to a specific commit):

```clojure
com.eldrix/hermes {:git/url "https://github.com/wardle/hermes.git"
                   :sha     "c008c7bf5b5f94bc27e25da17faa9d2e41e0a84c"}
```

### Maven/Gradle

```xml
<dependency>
  <groupId>com.eldrix</groupId>
  <artifactId>hermes</artifactId>
  <version>1.4.1582</version>
</dependency>
```

Add the Clojars repository:

```xml
<repositories>
  <repository>
    <id>clojars.org</id>
    <url>https://clojars.org/repo</url>
  </repository>
</repositories>
```

Check [Clojars](https://clojars.org/com.eldrix/hermes) for the latest version.

## Basic usage

The complete API documentation is available [https://cljdoc.org/d/com.eldrix/hermes](https://cljdoc.org/d/com.eldrix/hermes).


```clojure
(require '[com.eldrix.hermes.core :as hermes])

(with-open [svc (hermes/open "snomed.db")]

  ;; Concept lookup (0.82 us)
  (hermes/concept svc 24700007)

  ;; Preferred synonym (locale-aware)
  (hermes/preferred-synonym svc 24700007 "en-GB")

  ;; Search (141-184 us)
  (hermes/search svc {:s "heart attack" :max-hits 10})

  ;; Constrained search
  (hermes/search svc {:s "diabetes" :constraint "<64572001" :max-hits 10})

  ;; Subsumption (13-69 us)
  (hermes/subsumed-by? svc 24700007 6118003)
  ;; => true (multiple sclerosis IS-A demyelinating disease)

  ;; ECL expansion
  (hermes/expand-ecl svc "<<73211009")

  ;; All parents (transitive IS-A closure)
  (hermes/all-parents svc 24700007)

  ;; Cross-map to ICD-10
  (hermes/map-into svc 24700007 999002271000000101)

  ;; Properties
  (hermes/properties svc 24700007))
```

## Key functions

The `com.eldrix.hermes.core` namespace exposes ~50 public functions. All take 
a `svc` (service) as the first argument, obtained from `hermes/open`.

### Concepts and descriptions

| Function | Description |
|---|---|
| `concept` | Get a concept record by SCTID |
| `description` | Get a description record |
| `extended-concept` | Full concept with descriptions, relationships, refsets |
| `descriptions` | All descriptions for a concept |
| `synonyms` | Active synonyms for a concept |
| `preferred-synonym` | Locale-aware preferred term |
| `fully-specified-name` | FSN including semantic tag |

### Hierarchy and subsumption

| Function | Description |
|---|---|
| `all-parents` | Transitive IS-A closure (ancestors) |
| `all-children` | Transitive descendants |
| `subsumed-by?` | Is concept A a type of concept B? |
| `are-any?` | Do any of these concepts have a given ancestor? |
| `paths-to-root` | All IS-A paths to the root concept |
| `properties` | Defining relationships by group |

### Search

| Function | Description |
|---|---|
| `search` | Full-text search with optional ECL constraint |
| `ranked-search` | Search ranked by relevance (returns results even when not all tokens match) |
| `transitive-synonyms` | All synonyms for a concept and all descendants |

### ECL

| Function | Description |
|---|---|
| `expand-ecl` | Expand an ECL expression to a set of concept IDs |
| `expand-ecl-historic` | Expand with historical associations |
| `intersect-ecl` | Filter a set of concept IDs by ECL |
| `valid-ecl?` | Syntax-check an ECL expression |

### Cross-mapping

| Function | Description |
|---|---|
| `reverse-map` | Map from external code to SNOMED concepts |
| `map-into` | Classify a concept into a target set |
| `component-refset-items` | Reference set items for a component |

### Expressions

| Function | Description |
|---|---|
| `parse-expression` | Parse a compositional grammar expression |
| `render-expression` | Render with preferred synonyms |
| `validate-expression` | Validate syntax, concepts and MRCM constraints |
| `subsumes` | Expression-level subsumption |

## Thread safety

The service returned by `hermes/open` is thread-safe and designed for 
concurrent use. The underlying LMDB store is memory-mapped and read-only at 
runtime — multiple threads (and even multiple processes) can read the same 
database concurrently with no coordination overhead.

## Java / JVM language interop

Hermes provides a typed Java API via the `com.eldrix.hermes.client.Hermes` 
class (from the `com.eldrix/hermes-java-api` dependency, included transitively).
All SNOMED CT domain objects are returned as typed interfaces 
(`IConcept`, `IDescription`, `IExtendedConcept`, `IResult`, etc.) from the 
`com.eldrix.hermes.sct` package.

```java
import com.eldrix.hermes.client.Hermes;
import com.eldrix.hermes.sct.*;

try (Hermes hermes = Hermes.openLocal("snomed.db")) {

    // Concept lookup
    IConcept ms = hermes.concept(24700007L);

    // Preferred synonym (locale-aware)
    IDescription desc = hermes.preferredSynonym(24700007L, "en-GB");
    String term = hermes.preferredTerm(24700007L);

    // Search
    Hermes.SearchRequest req = Hermes.newSearchRequestBuilder()
        .setS("heart attack")
        .setMaxHits(10)
        .setConstraint("<64572001")
        .build();
    List<IResult> results = hermes.search(req);

    // Subsumption
    boolean isA = hermes.subsumedBy(24700007L, 6118003L);

    // ECL expansion
    List<IResult> ecl = hermes.expandEcl("<<73211009", false);

    // All parents (transitive IS-A closure)
    Collection<Long> parents = hermes.allParents(24700007L);

    // Reference set items
    List<IRefsetItem> items = hermes.componentRefsetItems(24700007L);

    // Parse and render compositional grammar expressions
    IExpression expr = hermes.parseExpression("24700007 |Multiple sclerosis|");
    String rendered = hermes.renderExpression(expr, "en-GB");

    // Expression subsumption
    Hermes.SubsumptionResult sub = hermes.subsumes(exprA, exprB);
}
```

### Key API areas

| Area | Methods |
|---|---|
| Concepts | `concept`, `concepts`, `extendedConcept` |
| Terminology | `preferredSynonym`, `preferredTerm`, `synonyms`, `matchLocale` |
| Hierarchy | `subsumedBy`, `isAConcept`, `allParents`, `allChildren`, `areAny`, `parentsOfType`, `childrenOfType` |
| Search | `search` (via `SearchRequestBuilder`) |
| ECL | `expandEcl`, `expandEclPreferred`, `intersectEcl`, `isValidEcl` |
| Expressions | `parseExpression`, `renderExpression`, `validateExpression`, `subsumes` |
| Reference sets | `componentRefsetItems`, `componentRefsetIds`, `refsetMembers`, `installedReferenceSets` |

See the [hermes-java-api](https://github.com/wardle/hermes-api) source for full
Javadoc and the complete list of methods.
