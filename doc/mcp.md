# MCP Server

Hermes provides a native [Model Context Protocol (MCP)](https://modelcontextprotocol.io) 
server, allowing AI assistants and LLM-based tools to query SNOMED CT directly.
The MCP server communicates via stdio using JSON-RPC 2.0.

## Setup

### Claude Code

```shell
claude mcp add --transport stdio --scope user hermes -- hermes --db /path/to/snomed.db mcp
```

This makes hermes available in all your Claude Code sessions. Use 
`--scope project` to add it to a specific project only, or `--scope local` 
for a local-only configuration.

To verify:

```shell
claude mcp list
```

### Claude Desktop

Add to your `claude_desktop_config.json`:

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

If using a jar file rather than Homebrew:

```json
{
  "mcpServers": {
    "hermes": {
      "command": "java",
      "args": ["-jar", "/path/to/hermes.jar", "--db", "/path/to/snomed.db", "mcp"]
    }
  }
}
```

### Options

Use `--locale` to set the default locale for preferred synonyms:

```shell
hermes --db snomed.db --locale en-GB mcp
```

Use `--owl` to enable OWL reasoning (requires [OWL setup](owl-reasoning.md)):

```shell
clj -M:run:owl --db snomed.db --owl mcp
```

## Tools

Hermes exposes 29 MCP tools. All tools that accept a `concept_id` parameter 
also accept an array of concept IDs, returning an array of results.

### Search

| Tool | Description |
|---|---|
| `search` | Ranked search — returns best matches even when not all tokens match. Best for "find me the best match for X". |
| `autocomplete` | All-tokens-must-match search, optimised for interactive type-ahead. Supports fuzzy matching (0-2 edit distance). |

Both support an optional `constraint` parameter (ECL expression) to scope results.

### Concept information

| Tool | Description |
|---|---|
| `concept` | Raw concept record (id, active, moduleId, definitionStatusId) |
| `extended_concept` | Full concept with descriptions, relationships, refset memberships and preferred synonym |
| `synonym` | Preferred synonym for a concept |
| `fully_specified_name` | Fully specified name including semantic tag |
| `descriptions` | All descriptions (all languages, all types) |
| `properties` | Defining attributes with human-readable labels |
| `paths_to_root` | All IS-A paths from a concept to the root, with labels |

### Hierarchy and subsumption

| Tool | Description |
|---|---|
| `subsumed_by` | Is concept A a type of concept B? Supports arrays (any-pair match). |
| `intersect_ecl` | Filter a list of concept IDs to those matching an ECL expression |

### ECL (Expression Constraint Language)

| Tool | Description |
|---|---|
| `expand_ecl` | Expand an ECL expression to matching concepts with preferred terms |
| `valid_ecl` | Syntax-check an ECL expression without executing it |

### Cross-mapping

| Tool | Description |
|---|---|
| `map_to` | Map a concept to a target code system (e.g. ICD-10). Walks the hierarchy if no direct map exists. |
| `map_from` | Reverse map from an external code back to SNOMED CT concepts |
| `map_into` | Classify a concept into the most specific ancestor(s) in a target set |

### Reference sets

| Tool | Description |
|---|---|
| `membership` | Reference sets a concept belongs to, with names |
| `refset_members` | All member concept IDs of a reference set |
| `refsets` | All installed reference sets, grouped by type |

### Historical

| Tool | Description |
|---|---|
| `historical` | Historical associations for inactive concepts (SAME AS, REPLACED BY, etc.) |
| `source_historical` | Inactive concepts that historically mapped TO this active concept |
| `with_historical` | Expand a concept to include all historical predecessors and successors |

### Expressions

| Tool | Description |
|---|---|
| `render_expression` | Render a compositional grammar expression with preferred synonyms |
| `validate_expression` | Validate an expression: syntax, concept existence, MRCM constraints |
| `expression_subsumes` | Test subsumption between two expressions (structural or OWL mode) |
| `refinements` | MRCM-permitted attributes for post-coordination of a concept |

### Other

| Tool | Description |
|---|---|
| `transitive_synonyms` | All synonyms for a concept and all its descendants |
| `server_info` | Installed releases, supported locales, installed reference sets |

## Resources

Hermes provides two built-in resources that AI assistants can reference:

| Resource | Description |
|---|---|
| `hermes://guides/ecl` | ECL v2.2 quick reference — all operators, filters, history supplements, concrete values and common patterns |
| `hermes://guides/concept-model` | SNOMED CT concept model — semantic tags, key relationship types, top-level hierarchies |

## Prompts

Four guided prompts encode best-practice workflows:

| Prompt | Arguments | Description |
|---|---|---|
| `clinical_coding` | `clinical_term`, `target_refset_id` (optional) | Step-by-step clinical coding: search, verify, check properties, paths, cross-map |
| `concept_exploration` | `concept_id` | Systematic concept exploration: extended concept, properties, hierarchy, historical |
| `value_set_construction` | `clinical_domain` | ECL-based value set authoring: search, draft ECL, refine, preview |
| `postcoordination` | `clinical_meaning` | Build a post-coordinated expression: find focus concept, check MRCM, validate |

## Example interactions

### Clinical coding

> "What SNOMED code should I use for a patient with type 2 diabetes and peripheral neuropathy?"

The AI will use `search` to find candidates, `extended_concept` to examine them,
`properties` to check defining attributes, `subsumed_by` to verify hierarchy 
position, and `map_to` to provide the ICD-10 mapping.

### Value set construction

> "I need a value set of all inflammatory bowel diseases for a clinical trial."

The AI will use the `value_set_construction` prompt to iteratively build an ECL 
expression, testing with `expand_ecl` and refining with `intersect_ecl`.

### Post-coordination

> "Build a SNOMED expression for 'open fracture of left neck of femur'."

The AI will use `refinements` to check MRCM-permitted attributes, construct the 
expression, validate it with `validate_expression`, and render it with 
`render_expression`.
