# Changes

This log documents significant changes for each release.

## [0.11.603] - not yet released

* Bump file-based database to 0.11 so application and database version matched
* Improve refset extended attribute support
* Add new search index 'members.db' for search of reference set members
* Partial ECL v2.0 support including +HISTORY and member filters
* Add support for lucene v8 usage when required (e.g. for java 8 compatibility)

## [0.10.533] - 2022-04-11

* New two-phase import permitting reification of refset items on import.
* Return refset items with extended attributes by default in graph and HTTP APIs.

## [0.10.519] - 2022-04-09 

* Improve speed and error handling in import
* Revise approach to extended fields in reference set items
* Bump to version 0.7 of the file-based database given changes in schema.
This means this version will refuse to read databases created in prior versions.
* Add runtime reification of refset items for those not reified during import. 

## [0.9.486] - 2022-04-03

* Add specifications for all supported SNOMED CT entities
* Add generative tests for parsing and unparsing SNOMED data files
* Add synth_test.clj to export and then re-import generated synthetic data
* Bump to Clojure 1.11.0
* Speed up import and indexing

## [0.9.458] - 2022-03-27

* Upgrade to Lucene 9.1
* Add and improve graph API resolvers
* Improve synthetic and live test suite
* Fix extended reference set import pattern matching

## [0.9.408] - 2022-03-21

* Add synthetic SNOMED data generation
* Much more complete automated testing using synthetic data
* Add greater instrumentation when in development or testing
* Add import of OWL reference set types 
* Fix accept-language parsing and fallback in http server

## [0.9.369] - 2022-02-17

* Bump to Lucene 9.0 - hermes now requires JDK 11 or newer.
* Switch to using clojure.data.json for JSON export.
* Bump other minor outdated dependencies (logback, trud).

## [0.9.362] - 2022-02-15

* Support for mapping concepts into a defined subset of concepts.
* Add get-synonyms convenience function
* Expose get-all-children at top-level API

## [0.8.341] - 2022-01-22

* Bump to Apache Lucene 8.11.0 - no API or index changes, but simply bug fixes
* Add --allowed-origins option to server to enable configuration of CORS, if required
* Add build via [tools.build](https://github.com/clojure/tools.build)
* Add deployment to clojars.

## [0.8.3] - 2021-11-04

* More complete UK language refset priority list
* Fail fast if there are any issues caught during processing (e.g. broken data such as invalid dates)
* Optimise import, parsing and storage flow. Better handling of errors during import.
* Fix import of simple reference sets, including when 'Simple' not in summary field of filename
* Tidy status output when importing data if no metadata found.
* Add support for simple reference set extensions with flexible fields based on 's' 'c' or 'i' in filename for local customisation

## [0.8.2] - 2021-10-31

* Status command now prints installed reference sets, and optionally counts of SNOMED data
* Add -v --verbose flag to increase verbosity for commands, although they're pretty verbose already
* Better error reporting during import 


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

* Fix handling of '<<' in expressions.
* Improve validation of HTTP parameters
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

* Add search and autocompletion using Apache Lucene

## [0.1.0] - 2020-11-12

* Basic SNOMED service (store/retrieval/inference)

