# Cross-mapping

Hermes supports bidirectional mapping between SNOMED CT and other code systems 
(ICD-10, OPCS, Read codes) as well as mapping into arbitrary reference sets for 
analytics.

## SNOMED to external code system

Map a SNOMED concept to an external code system using its map reference set.

```shell
# Multiple sclerosis → ICD-10
# 999002271000000101 is the UK ICD-10 complex map reference set
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007/map/999002271000000101' | jq .
```

[Try it live](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101)

```json
[
  {
    "active": true,
    "mapAdvice": "ALWAYS G35.X",
    "mapTarget": "G35X",
    "mapGroup": 1,
    "mapPriority": 1,
    "referencedComponentId": 24700007,
    "refsetId": 999002271000000101
  }
]
```

### Hierarchy walking

Not every SNOMED concept has a direct map. When a concept is not directly 
represented in a map reference set, hermes automatically walks up the IS-A 
hierarchy until it finds the nearest mapped ancestor.

This means you can record at maximum clinical granularity and still get correct 
external codes for reporting, billing and analytics — without maintaining manual 
lookup tables.

## External code to SNOMED

Map from an external code back to SNOMED concepts:

```shell
# ICD-10 G35.X → SNOMED concepts
curl -s 'http://localhost:8080/v1/snomed/crossmap/999002271000000101/G35X' | jq '.[] | .referencedComponentId'
```

[Try it live](http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X)

## Map-into for analytics

`map-into` classifies any SNOMED concept into a target reference set by walking 
up the hierarchy to find the most specific ancestor(s) that belong to that set.
This is powerful for reducing clinical granularity to reporting categories.

```shell
# Multiple sclerosis (24700007) is in the UK emergency unit reference set
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007/map/991411000000109' | jq .
# => returns the direct membership

# "Limbic encephalitis with LGI1 antibodies" (763794005) is NOT in the emergency
# unit refset — hermes walks up the hierarchy to "Encephalitis" (45170000) which is
curl -s 'http://localhost:8080/v1/snomed/concepts/763794005/map/991411000000109' | jq .
```

[Try it live: limbic encephalitis mapped into emergency refset](http://128.140.5.148:8080/v1/snomed/concepts/763794005/map/991411000000109)

This lets clinicians record with full SNOMED granularity while mapping to 
reporting categories on demand — reducing dimensionality for analytics without 
losing clinical precision.

The target can be any reference set — not just cross-map reference sets. This 
works with simple reference sets, allowing you to define your own analytics 
categories.

## Reference set memberships

Check which reference sets a concept belongs to:

```shell
curl -s 'http://localhost:8080/v1/snomed/concepts/24700007/refsets' | jq '.[] | .refsetId'
```

## Historical associations

When concepts are retired, SNOMED provides historical associations indicating 
what replaced them:

```shell
curl -s -H 'Accept-Language: en-GB' \
  'http://localhost:8080/v1/snomed/concepts/586591000000100/historical' | jq .
```

Association types include SAME AS, REPLACED BY, POSSIBLY EQUIVALENT TO and 
others. This is essential for maintaining data quality when concepts change 
between releases.
