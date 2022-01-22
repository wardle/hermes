# Changes

This log documents significant changes for each release.

## [0.8.341] - 2022-01-22

* Bump to Apache Lucene 8.11.0 - no API or index changes, but simply bug fixes
* Add --allowed-origins option to server to enable configuration of CORS, if required
* Add build via [tools.build](https://github.com/clojure/tools.build)
* Add deployment to clojars.

## [0.8.3] - 2021-11-04

* More complete UK language refset priority list
* Fail fast if there are any issues caught during processing (e.g. broken data such as invalid dates)
* Optimisations to import, parsing and storage flow. Better handling of errors during import.
* Fixed import of simple reference sets, including when 'Simple' not in summary field of filename
* Tidied status output when importing data if no metadata found.
* Added support for simple reference set extensions with flexible fields based on 's' 'c' or 'i' in filename for local customisation

## [0.8.2] - 2021-10-31

* status command now prints installed reference sets, and optionally counts of SNOMED data
* add -v --verbose flag to increase verbosity for commands, although they're pretty verbose already
* better error reporting during import 


## [0.8.1] - 2021-10-30

* Add explicit choice of locale at time of index creation for preferred concept cache. Hermes supports multiple languages but for convenience caches the preferred synonym for a given locale (or set of reference sets) in the search index.
* Fallback to en-US if chosen locale, or system default locale, does not have a known set of 'best' language reference sets

## [0.8.0] - 2021-10-24

* Add fuzzy and fallback-fuzzy search to graph API.
* Add get-all-parents, and parent-relationships to core API
* Permit configuration of with-historical to allow choice of association refset types
* Add ECL membership check
* Add reverse cross-map prefix search (useful for ICD-10 and Read)
* Optimisations

## [0.7.5] - 2021-09-13

* Fixed handling of '<<' in expressions.
* Better validation of HTTP parameters
* Backend paths-to-root and some-indexed for dimensionality reduction work

## [0.7.4] - 2021-07-27

* Make JSON default for HTTP server
* Add refsets HTTP endpoint 

## [0.7.3] - 2021-07-27

* Add reverse index specifically for association reference set items
* Add expand API endpoint to HTTP server, including historical if requested

## [0.7.2] - 2021-07-20

* Bind address specified at command line now actually used.

## [0.7.1] - 2021-06-04

* Add support for new SNOMED filename naming system used by UK since May 21
* Force UTF-8 encoding to ensure works on platforms with different default character encoding.

## [0.7.0] - 2021-05-31

* New graph API using declarative approach permitting clients to ask for exactly what they need
* Expose fuzzy search in REST API.
* Restructuring - separate library code from cli commands / server
* Add rudimentary github actions tests, but not yet using live db
* Add historical associations reference set support
* Update database version to v0.6 given new indexes.
* Better query re-writing for when single must-not clauses used alone. 

## [0.6.2] - 2021-04-20

* Permit prefix search to run with one character or more, rather than minimum of three, for better autocompletion functionality.

## [0.6.1] - 2021-04-19

* Upgrade dependencies
* Temporarily bind to 0.0.0.0 pending more complete server configuration options

## [0.6.0] - 2021-03-31

* Support for unlimited results when used as a library, and when processing expressions
* Harmonise web service key names to use camel case and not mix in kebab-case.
* Expose library API call to get installed reference sets
* Expose support for 'Accept-Language' using BCP 47 language tags in REST server, with additional support for specific language reference set extension of form en-gb-x-XXXX where XXXX is preferred language refset.  
* Release information included in log files

## [0.5.0] - 2021-03-09

* Minor fixes

## [0.4.0] - 2021-03-08

* Major change to backend store with new custom serialization resulting in enormous speed-up and size benefits.
* UK dictionary of medicines and devices (dm+d) custom code extracted to [another repository](https://github.com/wardle/dmd) 
* Add support for transitive synonyms

## [0.3.0] - 2021-01-27

* Add special custom extension for UK dictionary of medicines and devices (dm+d) to bring in non-SNOMED BSA data
* SNOMED ECL (expression constraint language) support
* SNOMED CG (compositional grammar) support  
* Major improvements to server and backend. Unified terminology service abstracting underlying implementations.

## [0.2.0] - 2020-11-18

* Added search and autocompletion using Apache Lucene

## [0.1.0] - 2020-11-12

* Basic SNOMED service (store/retrieval/inference)

