# Search and ECL constraints and expansions

## Full-text search

Hermes provides fast, locale-aware full-text search across all SNOMED CT 
descriptions. Search results are ranked by relevance and can be constrained 
by ECL expressions. You can use the Java, Clojure or HTTP APIs to do this, and use
the FHIR terminology $expand operation via the companion [hades](https://github.com/wardle/hades).

Here we give examples using the HTTP API.

### Basic search

```shell
curl -s -H 'Accept-Language: en-GB' \
  'http://localhost:8080/v1/snomed/search?s=heart+attack&maxHits=5' | jq '.[] | {term, conceptId}'
```

However, it would be very unusual indeed to search like that - you usually wish to constrain your search to part
of SNOMED CT through the use of an ECL expression acting as a constraint...

### Constrained search

```shell
# Only disorders
curl -s 'http://localhost:8080/v1/snomed/search?s=diabetes&constraint=<64572001&maxHits=10'

# Only procedures
curl -s 'http://localhost:8080/v1/snomed/search?s=hip&constraint=<71388002&maxHits=10'

# Only UK medicinal products
curl -s 'http://localhost:8080/v1/snomed/search?s=amlodipine&constraint=<10363601000001109&maxHits=500'
```

[Try it live: diabetes, constrained to disorders](http://128.140.5.148:8080/v1/snomed/search?s=diabetes&constraint=%3C64572001&maxHits=10)

### Autocompletion

For type-ahead user interfaces, use `fallbackFuzzy` and `removeDuplicates`:

```shell
curl -s 'http://localhost:8080/v1/snomed/search?s=amlo&fallbackFuzzy=true&removeDuplicates=true&constraint=<10363601000001109&maxHits=20'
```

- `fallbackFuzzy` retries with fuzzy matching if exact search returns no results,
  handling misspellings gracefully.
- `removeDuplicates` removes consecutive results with the same concept ID and 
  text, which can occur when multiple distributions are installed.

### Abbreviations

Hermes indexes all synonyms, so abbreviations work naturally:

```shell
# "MND" finds "Motor neurone disease"
curl -s 'http://localhost:8080/v1/snomed/search?s=mnd&constraint=<64572001&maxHits=5'
```

[Try it live](http://128.140.5.148:8080/v1/snomed/search?s=mnd&constraint=%3C64572001&maxHits=5)

### Inactive descriptions

By default, search includes inactive descriptions but excludes inactive concepts.
This is deliberate — a user searching for a now-inactive synonym like 
"Wegener's Granulomatosis" should still find the active concept 
"Granulomatosis with polyangiitis".

## Expression Constraint Language (ECL)

Hermes supports the full [ECL v2.2 specification](http://snomed.org/ecl). ECL 
lets you query the SNOMED CT ontology by structure — hierarchy, attributes, 
reference set membership and more.

### Hierarchy operators

```shell
# All descendants of diabetes mellitus (not including self)
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=<73211009&preferred=true'

# Self and all descendants
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=<<73211009&preferred=true'

# Direct children only
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=<!73211009&preferred=true'

# Ancestors
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=>>24700007&preferred=true'
```

### Logical operators

```shell
# Lung disorders associated with oedema (intersection)
curl -s -G 'http://localhost:8080/v1/snomed/expand' \
  --data-urlencode 'ecl=<19829001 AND <301867009' -d preferred=true

# Disorders excluding infectious ones
curl -s -G 'http://localhost:8080/v1/snomed/expand' \
  --data-urlencode 'ecl=<64572001 MINUS <<40733004' -d preferred=true

# Either clinical finding or procedure
curl -s -G 'http://localhost:8080/v1/snomed/expand' \
  --data-urlencode 'ecl=<404684003 OR <71388002' -d preferred=true
```

[Try it live: lung disorders with oedema](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C19829001%20AND%20%3C301867009&preferred=true)

### Attribute constraints

Query by defining relationships:

```shell
# Procedures on heart structures
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=<71388002:363704007=<<80891009&preferred=true'

# Products containing amoxicillin 250mg in oral dose form
curl -s -G 'http://localhost:8080/v1/snomed/expand' -d preferred=true \
  --data-urlencode 'ecl=< 763158003 : 411116001 = << 385268001, { << 127489000 = << 372687004, 1142135004 = #250, 732945000 = 258684004 }'
```

[Try it live: procedures on heart structures](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C71388002:363704007=%3C%3C80891009&preferred=true)

### Reference set members

```shell
# Members of the UK emergency care reference set
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=^991411000000109&preferred=true'

# Members of any simple reference set
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=^*&preferred=true'
```

### History supplements (ECL v2.2)

Include historical associations so you don't miss data coded with now-inactive 
concepts. Essential for analytics over longitudinal data.

```shell
# Asthma and all subtypes, including historical associations
curl -s -G 'http://localhost:8080/v1/snomed/expand' \
  --data-urlencode 'ecl=<<195967001 {{ +HISTORY-MOD }}' -d preferred=true
```

[Try it live](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C%3C195967001%20%7B%7B%20%2BHISTORY-MOD%20%7D%7D&preferred=true)

Three history profiles are available:

| Profile | Description |
|---|---|
| `HISTORY-MIN` | SAME AS associations only |
| `HISTORY-MOD` | SAME AS and POSSIBLY EQUIVALENT TO |
| `HISTORY-MAX` | All historical association types |

### Description filters (ECL v2.2)

```shell
# Concepts with a description containing "heart" that is a synonym
curl -s -G 'http://localhost:8080/v1/snomed/expand' \
  --data-urlencode 'ecl=<< 64572001 {{ term = "heart", type = syn }}' -d preferred=true
```

### Pipe syntax

ECL supports inline term labels using pipe syntax for readability:

```
< 19829001 |Disorder of lung| AND < 301867009 |Edema of trunk|
```

The labels are for human readability only as per the ECL specification. 

### Concept ID as ECL

A bare concept identifier is a valid ECL expression. This is useful with 
`includeHistoric` to expand a single concept to include its historical 
associations:

```shell
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true'
```

### Search vs expand

Use **search** (`/v1/snomed/search`) when:
- You have user-typed text and want ranked, relevant results
- You're building autocompletion for a user interface
- You want to combine free-text with ECL constraints

Use **expand** (`/v1/snomed/expand`) when:
- You have an ECL expression and want all matching concepts
- You're building a value set
- You need to enumerate members of a reference set
- You don't have user-typed text

## Building codelists

For research and analytics, [codelists](https://github.com/wardle/codelists) 
provides declarative codelist generation on top of hermes. Define codelists 
using ECL, ICD-10 and ATC codes in a single specification and generate 
reproducible, versioned outputs.

It's almost always better to define codelists using ECL, or the codelists JSON standard,
than manually curate a list of codes by hand. With open and versioned software code, versioned SNOMED CT and versioned ECL,
codelists can be dynamically generated in a reproducible and consistent manner.
