# HTTP API

Start the server:

```shell
hermes --db snomed.db --port 8080 serve
```

Options:

| Flag | Default | Description |
|---|---|---|
| `--port` | 8080 | Port number |
| `--bind-address` | — | Bind address (use `0.0.0.0` for containers) |
| `--allowed-origins` | — | CORS policy: `"*"` or comma-delimited hostnames |
| `--locale` | auto | Default locale (e.g. `en-GB`). Auto-detected from installed language reference sets. |

Data is returned as JSON by default. Request EDN by setting `Accept: application/edn`.
Locale-aware endpoints respect the `Accept-Language` header.

## Endpoint summary

| Endpoint | Description |
|---|---|
| `GET /v1/snomed/concepts/:id` | Concept record |
| `GET /v1/snomed/concepts/:id/extended` | Full concept with descriptions, relationships, refsets |
| `GET /v1/snomed/concepts/:id/descriptions` | All descriptions |
| `GET /v1/snomed/concepts/:id/preferred` | Preferred synonym (locale-aware) |
| `GET /v1/snomed/concepts/:id/properties` | Defining relationships and concrete values |
| `GET /v1/snomed/concepts/:id/historical` | Historical associations for inactive concepts |
| `GET /v1/snomed/concepts/:id/refsets` | Reference set memberships |
| `GET /v1/snomed/concepts/:id/subsumed-by/:subsumer-id` | Subsumption check |
| `GET /v1/snomed/concepts/:id/map/:refset-id` | Cross-map to a target code system or reference set |
| `GET /v1/snomed/crossmap/:refset-id/:code` | Reverse map from external code to SNOMED |
| `GET /v1/snomed/search` | Full-text search with ECL constraints |
| `GET /v1/snomed/expand` | Expand an ECL expression |
| `GET /v1/snomed/intersect` | Test concept IDs against an ECL expression |
| `GET /v1/snomed/subsumes` | Expression-level subsumption |
| `GET /v1/snomed/classify` | OWL classification (requires `--owl`) |
| `GET /v1/snomed/necessary-normal-form` | Necessary Normal Form (requires `--owl`) |
| `GET /v1/snomed/status` | Installed distributions and module info |

## Concept endpoints

### Get a concept

```shell
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007' | jq .
```

```json
{
  "active": true,
  "definitionStatusId": 900000000000074008,
  "effectiveTime": "2002-01-31",
  "id": 24700007,
  "moduleId": 900000000000207008
}
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007](http://128.140.5.148:8080/v1/snomed/concepts/24700007)

### Get extended concept

Returns the concept with all descriptions, parent relationships (with 
transitive closures), concrete values and reference set memberships. If an 
`Accept-Language` header is provided, includes a `preferredDescription`.

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007/extended](http://128.140.5.148:8080/v1/snomed/concepts/24700007/extended)

```shell
curl -s -H 'Accept-Language: en-GB' 'http://localhost:8080/v1/snomed/concepts/24700007/extended' | jq .
```

The response includes all descriptions (with `acceptableIn` and `preferredIn` 
arrays indicating language reference set status), parent relationships with full 
transitive closures for each relationship type, and reference set memberships. 
Clients can use the transitive closures for local subsumption checks without 
further server round-trips.

<details>
<summary>Full response example (multiple sclerosis, 24700007)</summary>

```json
{
    "concept": {
        "active": true,
        "definitionStatusId": 900000000000074008,
        "effectiveTime": "2002-01-31",
        "id": 24700007,
        "moduleId": 900000000000207008
    },
    "descriptions": [
        {
            "acceptableIn": [],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 41398015,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [900000000000509007, 900000000000508004, 999001261000000100],
            "refsets": [900000000000509007, 900000000000508004, 999001261000000100],
            "term": "Multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [900000000000509007, 900000000000508004, 999001261000000100],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 1223979019,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [900000000000509007, 900000000000508004, 999001261000000100],
            "term": "Disseminated sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [900000000000509007, 900000000000508004, 999001261000000100],
            "active": true,
            "caseSignificanceId": 900000000000017005,
            "conceptId": 24700007,
            "effectiveTime": "2003-07-31",
            "id": 1223980016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [900000000000509007, 900000000000508004, 999001261000000100],
            "term": "MS - Multiple sclerosis",
            "typeId": 900000000000013009
        }
    ],
    "directParentRelationships": {
        "116676008": [409774005, 32693004],
        "116680003": [6118003, 413834006, 128283000, 39367000],
        "263502005": [90734009],
        "363698007": [21483005],
        "370135005": [769247005]
    },
    "parentRelationships": {
        "116676008": [138875005, 107669003, 123037004, 409774005, 32693004, 49755003, 118956008],
        "116680003": [
            6118003, 138875005, 404684003, 27624003, 413834006, 128139000,
            23853001, 246556002, 363170005, 64572001, 118940003, 414029004,
            362975008, 363171009, 128283000, 39367000, 80690008, 362965005
        ],
        "263502005": [138875005, 288524001, 90734009, 303102005, 281586009, 362981000, 272099008, 272103003],
        "363698007": [138875005, 21483005, 442083009, 1193638008, 123037004, 25087005, 91689009, 714488006, 91723000],
        "370135005": [138875005, 769247005, 308489006, 303102005, 281586009, 362981000, 719982003]
    },
    "refsets": [
        1322291000000109, 1382531000000102, 991381000000107, 999002271000000101,
        991411000000109, 733073007, 1127601000000107, 900000000000497000
    ]
}
```

Each description includes `acceptableIn` and `preferredIn` arrays indicating 
which language reference sets consider it acceptable or preferred. The 
`parentRelationships` contain the full transitive closure for each relationship 
type, enabling client-side subsumption checks without further server calls.

</details>

### Get preferred synonym

Locale-aware preferred term:

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/80146002/preferred](http://128.140.5.148:8080/v1/snomed/concepts/80146002/preferred)

```shell
curl -s -H 'Accept-Language: en-GB' 'http://localhost:8080/v1/snomed/concepts/80146002/preferred' | jq .term
# => "Appendicectomy"

curl -s -H 'Accept-Language: en-US' 'http://localhost:8080/v1/snomed/concepts/80146002/preferred' | jq .term
# => "Appendectomy"
```

### Get properties

Returns defining relationships grouped by role group, optionally with 
human-readable labels.

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties?format=id:syn](http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties?format=id:syn)

```shell
curl -s -H 'Accept-Language: en-GB' 'http://localhost:8080/v1/snomed/concepts/1231295007/properties?format=id:syn' | jq .
```

```json
{
  "0": {
    "116680003:Is a": ["779653004:Lamotrigine only product in oral dose form"],
    "411116001:Has manufactured dose form": "385060002:Prolonged-release oral tablet",
    "763032000:Has unit of presentation": "732936001:Tablet",
    "1142139005:Count of base of active ingredient": "#1"
  },
  "1": {
    "732943007:Has BoSS": "387562000:Lamotrigine",
    "732945000:Has presentation strength numerator unit": "258684004:mg",
    "732947008:Has presentation strength denominator unit": "732936001:Tablet",
    "762949000:Has precise active ingredient": "387562000:Lamotrigine",
    "1142135004:Has presentation strength numerator value": "#250",
    "1142136003:Has presentation strength denominator value": "#1"
  }
}
```

Parameters:

| Parameter | Description |
|---|---|
| `expand` | Include transitive (inherited) relationships (`true`/`false`) |
| `format` | Format both keys and values |
| `key-format` | Format for relationship type keys |
| `value-format` | Format for target values |

Format options: `id`, `syn`, `id:syn`, `[id:syn]`, `{id:syn}`.

When results are not expanded, the MRCM metadata model is used to determine 
cardinality — singular attributes are returned as scalars rather than arrays.

### Subsumption

```shell
# Is multiple sclerosis a type of demyelinating disease?
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007/subsumed-by/6118003' | jq .
# => {"subsumedBy": true}
```

## Search

```shell
curl -s 'http://localhost:8080/v1/snomed/search?s=mnd&constraint=<64572001&maxHits=5' | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/search?s=mnd&constraint=%3C64572001&maxHits=5)

```json
[
  {
    "id": 5372519013,
    "conceptId": 37340000,
    "term": "MND - motor neuron disease",
    "preferredTerm": "Motor neuron disease"
  },
  {
    "id": 486696014,
    "conceptId": 37340000,
    "term": "MND - Motor neurone disease",
    "preferredTerm": "Motor neuron disease"
  },
  {
    "id": 5372732017,
    "conceptId": 37340000,
    "term": "MND - motor neurone disease",
    "preferredTerm": "Motor neuron disease"
  }
]
```

See [Search and ECL](search-and-ecl.md) for full details.

Parameters:

| Parameter | Default | Description |
|---|---|---|
| `s` | — | Search text |
| `constraint` / `ecl` | — | ECL expression to constrain results |
| `maxHits` | — | Maximum number of results (1-9999) |
| `isA` | — | Constrain to descendants of this concept |
| `refset` | — | Constrain to members of this reference set |
| `fuzzy` | `false` | Enable fuzzy matching |
| `fallbackFuzzy` | `false` | Retry with fuzzy matching if exact search returns no results |
| `inactiveConcepts` | `false` | Include inactive concepts |
| `inactiveDescriptions` | `true` | Include inactive descriptions (recommended) |
| `removeDuplicates` | `false` | Remove consecutive results with same conceptId and text |
| `fsn` | `false` | Include fully specified names in results |

The default of searching inactive descriptions is deliberate — a user searching 
for a now-inactive synonym like "Wegener's Granulomatosis" should still find 
the active concept.

### More search examples

Search for UK medicinal products containing amlodipine, with fuzzy fallback 
and duplicate removal for autocompletion:

```shell
curl -s 'http://localhost:8080/v1/snomed/search?s=amlodipine&constraint=<10363601000001109&fallbackFuzzy=true&removeDuplicates=true&maxHits=500' | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/search?s=amlodipine&constraint=%3C10363601000001109&fallbackFuzzy=true&removeDuplicates=true&maxHits=500)

No search term is needed — you can search with just an ECL constraint. 
All drugs with exactly three active ingredients:

```shell
curl -s -G 'http://localhost:8080/v1/snomed/search' --data-urlencode 'constraint=<373873005|Pharmaceutical / biologic product| : [3..3] 127489000 |Has active ingredient| = < 105590001 |Substance|' | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/search?constraint=%3C373873005%7CPharmaceutical%20/%20biologic%20product%7C%20:%20%5B3..3%5D%20%20127489000%20%7CHas%20active%20ingredient%7C%20%20=%20%3C%20%20105590001%20%7CSubstance%7C)

All disorders of the lung associated with oedema:

```shell
curl -s -G 'http://localhost:8080/v1/snomed/search' --data-urlencode 'constraint=<19829001 AND <301867009' | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/search?constraint=%3C19829001%20AND%20%3C301867009)

## ECL expansion

If you are expanding an ECL expression without search terms, use the `expand` 
endpoint.

```shell
curl -s -G 'http://localhost:8080/v1/snomed/expand' --data-urlencode 'ecl=<19829001 AND <301867009' -d includeHistoric=true | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C19829001%20AND%20%3C301867009&includeHistoric=true)

See [Search and ECL](search-and-ecl.md) for full details.

Parameters:

| Parameter | Description |
|---|---|
| `ecl` | ECL expression (required) |
| `preferred` | Include preferred term for each concept |
| `dialectId` | Language reference set SCTID for preferred terms (overrides `Accept-Language`) |
| `includeHistoric` | Expand to include historical associations |

### History supplements

SNOMED ECL v2.0 introduced dedicated [history supplement functionality](https://confluence.ihtsdotools.org/display/DOCECL/6.11+History+Supplements).
For example, this returns asthma and all subtypes, including those now 
considered inactive or duplicate:

```
<<195967001 |Asthma| {{ +HISTORY-MOD }}
```

[Try it live](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C%3C%20195967001%20%7CAsthma%7C%20%7B%7B%20%2BHISTORY-MOD%20%7D%7D)

As a concept identifier is a valid ECL expression, you can find historical 
associations for a single concept:

```shell
curl -s 'http://localhost:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true' | jq .
```

Try it live: [http://128.140.5.148:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true](http://128.140.5.148:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true)

### Concrete values in ECL

ECL supports concrete values. Here are all products containing 250mg of 
amoxicillin in an oral dose form:

```
< 763158003 |Medicinal product (product)| :
     411116001 |Has manufactured dose form (attribute)|  = <<  385268001 |Oral dose form (dose form)| ,
    {    <<  127489000 |Has active ingredient (attribute)|  = <<  372687004 |Amoxicillin (substance)| ,
          1142135004 |Has presentation strength numerator value (attribute)|  = #250,
         732945000 |Has presentation strength numerator unit (attribute)|  =  258684004 |milligram (qualifier value)|}
```

Try it live: [http://128.140.5.148:8080/v1/snomed/expand?ecl=<763158003...](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C%20763158003%20%7CMedicinal%20product%20%28product%29%7C%20%3A%0A%20%20%20%20%20411116001%20%7CHas%20manufactured%20dose%20form%20%28attribute%29%7C%20%20%3D%20%3C%3C%20%20385268001%20%7COral%20dose%20form%20%28dose%20form%29%7C%20%2C%0A%20%20%20%20%7B%20%20%20%20%3C%3C%20%20127489000%20%7CHas%20active%20ingredient%20%28attribute%29%7C%20%20%3D%20%3C%3C%20%20372687004%20%7CAmoxicillin%20%28substance%29%7C%20%2C%0A%20%20%20%20%20%20%20%20%20%201142135004%20%7CHas%20presentation%20strength%20numerator%20value%20%28attribute%29%7C%20%20%3D%20%23250%2C%0A%20%20%20%20%20%20%20%20%20732945000%20%7CHas%20presentation%20strength%20numerator%20unit%20%28attribute%29%7C%20%20%3D%20%20258684004%20%7Cmilligram%20%28qualifier%20value%29%7C%7D)

## ECL intersection

Given a list of concept identifiers, return the subset that satisfy an ECL 
expression as a JSON array of concept identifiers. For example, you can check 
whether a list of a patient's diagnoses or problems meet a particular constraint 
without expanding the full result set.

```shell
curl -s 'http://localhost:8080/v1/snomed/intersect?ecl=<<6118003&conceptId=24700007&conceptId=26929004' | jq .
```

```json
[24700007]
```

Parameters:

| Parameter | Required | Description |
|---|---|---|
| `ecl` | Yes | ECL expression |
| `conceptId` | Yes | One or more concept identifiers to test (repeat for multiple) |
| `includeHistoric` | No | Expand input concept IDs to include historical associations before intersecting |

Historical associations can also be handled via ECL history supplements 
(e.g. `<<6118003 {{ +HISTORY-MIN }}`) — the `includeHistoric` parameter is 
provided for convenience and consistency with the `expand` endpoint.

## Cross-mapping

Map multiple sclerosis (`24700007`) into ICD-10 using the UK complex map 
reference set (`999002271000000101`):

```shell
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007/map/999002271000000101' | jq .
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101)

And crossmap back from ICD-10 to SNOMED:

```shell
curl -s 'http://localhost:8080/v1/snomed/crossmap/999002271000000101/G35X' | jq .
```

Try it live: [http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X](http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X)

If you map a concept that isn't directly in a reference set, hermes walks up 
the hierarchy to find the best parent match. Here "limbic encephalitis with 
LGI1 antibodies" (`763794005`) isn't in the UK emergency unit reference set 
(`991411000000109`), but "encephalitis" (`45170000`) is:

```shell
curl -s 'http://localhost:8080/v1/snomed/concepts/763794005/map/991411000000109' | jq .
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/763794005/map/991411000000109](http://128.140.5.148:8080/v1/snomed/concepts/763794005/map/991411000000109)

This makes it straightforward to allow clinicians to record highly-specific 
terms while mapping to less granular categories for analytics on demand.

See [Cross-mapping](cross-mapping.md) for full details.

## Expression subsumption

Tests whether expression A subsumes expression B.

```shell
curl -s 'http://localhost:8080/v1/snomed/subsumes?a=73211009&b=73211009:363698007=84301002' | jq .
```

| Parameter | Required | Description |
|---|---|---|
| `a` | Yes | SCG expression or concept ID |
| `b` | Yes | SCG expression or concept ID |
| `mode` | No | `structural` (default) or `owl` (requires `--owl` at startup) |

Returns `{"outcome": "equivalent"}`, `{"outcome": "subsumes"}`, 
`{"outcome": "subsumed-by"}`, or `{"outcome": "not-subsumed"}`.

## OWL reasoning endpoints

These require starting the server with `--owl`. See [OWL reasoning](owl-reasoning.md).

### Classify expression

```shell
curl -s 'http://localhost:8080/v1/snomed/classify?expression=73211009:363698007=84301002' | jq .
```

Returns equivalent concepts, direct super-concepts and proximal primitive supertypes.

### Necessary Normal Form

```shell
curl -s 'http://localhost:8080/v1/snomed/necessary-normal-form?expression=73211009:363698007=84301002' | jq .
```

## JSON identifier handling

SNOMED CT identifiers are 64-bit positive integers. Most JSON parsers handle 
these correctly, but some (notably JavaScript's default `JSON.parse`) may silently 
truncate large numbers. If your client has this issue, use a 
[reviver parameter](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/JSON/parse#using_the_reviver_parameter).
See [discussion](https://github.com/wardle/hermes/issues/50).
