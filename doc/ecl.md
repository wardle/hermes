# ECL Implementation Status

This document tracks which [Expression Constraint Language](http://snomed.org/ecl) 
features Hermes supports. For usage examples with the HTTP API, see 
[search-and-ecl.md](search-and-ecl.md).

Hermes parses the full ECL v2.2 grammar. The parser accepts all valid ECL 
expressions; limitations are in the evaluator, which throws an exception for 
unsupported clauses. This means adding support for a feature never requires 
changing the grammar — only adding evaluation logic.

## ECL Core and Extended profiles

ECL v2.3 (March 2026) introduced two conformance profiles, defined by 
separate ABNF grammars in the 
[IHTSDO ECL repository](https://github.com/IHTSDO/snomed-expression-constraint-language):

- **ECL Core** — a minimal subset for terminology servers. Covers hierarchy 
  operators, refinements (expression, numeric, string and boolean comparison), 
  compound expressions, attribute groups (without cardinality), dot notation, 
  memberOf, concept active filter, history supplements, and comments. No 
  cardinality, no reverse flag, no description or member filters.
- **ECL Extended** — everything in Core plus cardinality, reverse flag, 
  description/concept/member filters, dialect filters, alternate identifiers, 
  and reference set field selection.

Hermes implements most of ECL Core. The remaining Core gaps — all planned — 
are: string and boolean concrete refinements, `> *` and `>! *` wildcards, 
disjunction (OR) and parenthesized conditions within attribute groups, and 
two v2.3 additions (`^R` refsetContainingAny and `active = *`). Hermes also 
implements most of ECL Extended. See the summary table below for complete 
status.

## Summary

| Feature | Profile | Status |
|---|---|---|
| Descendant of (`<`) | Core | Yes |
| Descendant or self of (`<<`) | Core | Yes |
| Child of (`<!`) | Core | Yes |
| Child or self of (`<<!`) | Core | Yes |
| Ancestor of (`>`) | Core | Yes |
| Ancestor or self of (`>>`) | Core | Yes |
| Parent of (`>!`) | Core | Yes |
| Parent or self of (`>>!`) | Core | Yes |
| Top of set (`!!>`) | Core | Yes |
| Bottom of set (`!!<`) | Core | Yes |
| Wildcard (`*`) | Core | Yes |
| Wildcard `> *`, `>! *` | Core | Planned |
| Concept references with pipe terms | Core | Yes |
| Conjunction (`AND`, `,`) | Core | Yes |
| Disjunction (`OR`) | Core | Yes |
| Exclusion (`MINUS`) | Core | Yes |
| Nested expressions `( )` | Core | Yes |
| Attribute refinement (`:`) | Core | Yes |
| Expression comparison (`=`, `!=`) | Core | Yes |
| Numeric concrete (`#value`) | Core | Yes |
| String concrete (`"value"`) | Core | Planned |
| Boolean concrete (`true`/`false`) | Core | Planned |
| Attribute groups `{ }` | Core | Yes |
| Dot notation (`.`) | Core | Yes |
| MemberOf (`^`) | Core | Yes |
| MemberOf with wildcard (`^ *`) | Core | Yes |
| RefsetContainingAny (`^R`) | Core | Planned |
| Concept active filter | Core | Yes |
| Active filter wildcard (`active = *`) | Core | Planned |
| History supplements | Core | Yes |
| Comments (`/* */`) | Core | Yes |
| Attribute cardinality (`[min..max]`) | Extended | Yes |
| Attribute group cardinality | Extended | Planned |
| Cardinality + reverse flag | Extended | Planned |
| `[0..0]` with `!=` | Extended | Planned |
| Reverse flag (`R`) | Extended | Yes |
| Refset field selection (`^ [field]`) | Extended | No |
| Description term filter | Extended | Yes |
| Description type filter | Extended | Yes |
| Description dialect filter (`=` only) | Extended | Yes |
| Description active filter | Extended | Yes |
| Description module filter | Extended | Planned |
| Description effectiveTime filter | Extended | No |
| Description ID filter | Extended | Planned |
| Description language filter | Extended | No |
| Concept definition status filter | Extended | No |
| Concept module filter | Extended | No |
| Concept effectiveTime filter | Extended | No |
| Member active filter | Extended | Yes |
| Member module filter | Extended | Yes |
| Member effectiveTime filter | Extended | Yes |
| Member field filters (no time) | Extended | Yes |
| Alternate identifiers | Extended | No |

## Supported features

### Hierarchy operators

All ten hierarchy operators are supported: `<`, `<<`, `<!`, `<<!`, `>`, `>>`, 
`>!`, `>>!`, `!!>`, and `!!<`. Wildcards work with `<< *`, `>> *`, `< *`, 
`<! *`, `<<! *`, and `>>! *`. Wildcards with `> *` and `>! *` are planned.

### Compound expressions

Conjunction (`AND` and `,`), disjunction (`OR`), and exclusion (`MINUS`) are 
all supported, including nested parenthesized expressions.

### Refinements

Attribute refinements with expression comparison operators (`=`, `!=`) and 
numeric concrete values (`#250`, `#62.5`) with all comparison operators 
(`=`, `!=`, `<`, `<=`, `>`, `>=`) are supported.

String concrete refinements (e.g., `3460481009 = "PANADOL"`) and boolean 
concrete refinements (e.g., `* : * = true`) are not yet supported.

### Attribute groups

Attribute groups with same-group semantics are supported. Multiple attributes 
within `{ }` are conjunctive (AND). Disjunction (OR) within groups and 
parenthesized group conditions are not yet supported.

### Cardinality

Cardinality constraints on individual attributes are supported: `[0..0]`, 
`[1..1]`, `[0..1]`, `[2..*]`, etc. Group cardinality (`[1..3] { ... }`), 
combined cardinality with reverse flag (`[3..3] R 127489000 = *`), and 
`[0..0]` with `!=` (universal quantification) are not yet supported.

### Dot notation

Single and chained dot notation for attribute value navigation is supported:
`. 363698007` and `. 363698007 . 116680003`.

### MemberOf

MemberOf (`^`) is supported with concept IDs (`^ 723264001`), wildcards 
(`^ *` for all installed reference sets), and nested expressions 
(`^ (<< 723264001)`). Member filter constraints can be applied to refine 
results.

Reference set field selection (`^ [targetComponentId] ...`) is not supported.

### Reverse flag

The reverse flag (`R`) is supported on simple attributes. It is not yet 
supported in combination with cardinality constraints.

### Description filters

Supported description filters:

- **Term filter**: match (`term = "heart attack"`) and wildcard 
  (`term = wild:"*sclero*"`) with locale-aware case folding
- **Type filter**: by token (`type = syn`, `type = fsn`, `type = def`) and 
  by ID (`typeId = 900000000000013009`)
- **Dialect filter**: by alias (`dialect = en-gb`) and by ID 
  (`dialectId = 999001261000000100`), including dialect sets 
  (`dialect = (en-gb en-us)`). Only the `=` operator is supported; `!=` is 
  not yet implemented, nor are per-dialect acceptability annotations 
  (e.g. `dialect = (en-gb preferred)`)
- **Active filter**: `active = true` / `active = 1`

### Concept filters

Only the active filter is supported (`{{ C active = true }}`). Definition 
status, module, and effectiveTime filters are not planned — see below.

### Member filters

All member filter types are supported:

- **Active filter**: `{{ M active = true }}`
- **Module filter**: `{{ M moduleId = ... }}` with `=` and `!=`
- **EffectiveTime filter**: with all comparison operators
- **Field filters**: numeric, boolean, string (typed search), and expression 
  comparison on arbitrary reference set member fields (e.g., 
  `{{ M mapTarget = "J45.9" }}`). Time comparison on arbitrary fields is not 
  yet supported (only the dedicated `effectiveTime` filter handles dates)

### History supplements

All history profiles are supported: `{{ +HISTORY }}`, `{{ +HISTORY-MIN }}`, 
`{{ +HISTORY-MOD }}`, `{{ +HISTORY-MAX }}`, and custom history subsets 
(`{{ +HISTORY (subexpression) }}`).

### Comments

Block comments (`/* ... */`) are supported.

## Planned

Features that will be implemented:

| Feature | Notes |
|---|---|
| `> *` and `>! *` | Return all non-leaf concepts. Trivial to add. |
| Per-dialect acceptability annotations | `dialect = (en-gb preferred)` syntax parses but the evaluator does not yet treat the acceptability token as distinct from a dialect alias. |
| String concrete refinements | e.g., `3460481009 = "PANADOL"`. Concrete string values in attribute refinements. |
| Boolean concrete refinements | e.g., `* : * = true`. |
| OR within attribute groups | Disjunction inside `{ }` syntax. |
| Parenthesized group conditions | `{ (attr = val, attr2 = val) }` syntax. |
| Attribute group cardinality | `[1..3] { 127489000 = < 105590001 }`. Cardinality on groups, not individual attributes. |
| Cardinality + reverse flag | `[3..3] R 127489000 = *`. Combined constraint. |
| `[0..0]` with `!=` | Universal quantification. Requires care to implement correctly. |
| Dialect filter `!=` | Dialect filters currently only support the `=` operator. |
| Description module filter | The index already stores `module-id`; only evaluator wiring is needed. |
| Description ID filter | The index already stores `description-id` with query helpers in place. |

### ECL v2.3 features

These require upgrading the ABNF grammar from v2.2 to v2.3:

| Feature | Notes |
|---|---|
| `^R` (refsetContainingAny) | Inverse of memberOf — concepts that contain matching reference set members. |
| Active filter wildcard (`active = *`) | Match both active and inactive. Trivial addition. |
| Simplified concrete string matching | `concreteString` replaces `typedSearchTerm` in attribute value matching. Simplifies both grammar and evaluator. |

## Not currently supported

These features are parsed by the grammar but aren't currently implemented. 
Most would require expanding the search index — increasing database size for 
all users — to serve use cases that are primarily relevant to authoring tools 
rather than clinical or analytic use. Others, such as the language filter, are 
omitted because they are deprecated in the ECL specification and superseded by 
better-supported constructs. If you have a use case for one of these features, 
please open an issue and explain the clinical, analytic, or interoperability 
need — none of these are closed doors.

| Feature | Rationale |
|---|---|
| Concept definition status filter | Requires indexing definition status. Primarily useful in authoring (distinguishing primitive from fully defined concepts). |
| Concept module filter | Requires indexing module ID on concepts. Authoring and release management use case. |
| Concept effectiveTime filter | Requires indexing effective time on concepts. Authoring and release management use case. |
| Description effectiveTime filter | Requires indexing effective time on descriptions. Authoring and release management use case. |
| Alternate identifiers | Requires external identifier mapping infrastructure (e.g., LOINC scheme aliases). Cross-terminology mapping is handled via reference set member filters instead. |
| Refset field selection | `^ [targetComponentId] ...` — returns specific fields from reference set members rather than concepts. Complex to implement and a niche use case. |
| Language filter | Deprecated in the ECL specification and semantically weaker than dialect filters. `language = en` only matches the coarse two-letter description language code; it cannot express language reference set membership or acceptability (for example en-GB preferred vs acceptable). Hermes intentionally supports dialect filters instead, because they match how SNOMED CT language selection is performed in practice. |
