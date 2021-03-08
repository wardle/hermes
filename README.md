# Hermes : terminology tools, library and microservice.

>
> Hermes:  "Herald of the gods."
>

[![Scc Count Badge](https://sloc.xyz/github/wardle/hermes)](https://github.com/wardle/hermes/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hermes?category=cocomo&avg-wage=100000)](https://github.com/wardle/hermes/)

Hermes provides a set of terminology tools built around SNOMED CT including:

* a fast RESTful terminology server with full-text search functionality; ideal for driving autocompletion in user interfaces
* an inference engine in order to analyse SNOMED CT expressions and concepts and derive meaning
* cross-mapping to and from other code systems
* support for SNOMED CT compositional grammar and the SNOMED CT expression constraint language.

It is designed as both library for embedding into larger applications, or as a microservice. 

It is extremely fast using memory-mapped files and a file-based database of 1.7Gb,
small enough to fit into memory on a server. 

It replaces previous similar tools written in java and golang and is designed to fit into a wider architecture
with identifier resolution, mapping and semantics as first-class abstractions.

### A. How to download and build a terminology service

Ensure you have a pre-built jar file, or the source code checked out from github. See below for build instructions.

I'd recommend installing clojure and running using source code.

#### 1. Download and install at least one distribution.

You can either do this automatically, if your local distributor is supported,
or manually.

##### i) Use a registered SNOMED CT distributor to automatically download and import

There is currently only support for automatic download and import for the UK,
but other distribution sources can be added.

The basic command is:

```shell
clj -M:run --db snomed.db download <distribution-identifier> [properties] 
```

or if you are using a precompiled jar:
```shell
java -jar hermes.jar --db snomed.db download <distribution-identifier> [properties]
```

The distribution, as defined by `distribution-identifier`, will be downloaded
and imported to the file-baed database `snomed.db`. 

| Distribution-identifier | Description                                        |
|-------------------------|--------------------------------------------------- |
| uk.nhs/sct-clinical     | UK SNOMED CT clinical - incl international release |
| uk.nhs/sct-drug-ext     | UK SNOMED CT drug extension - incl dm+d            |


Each distribution might require custom configuration options. These
can be given as key value pairs after the command, and their use will depend
on which distribution you are using.

For example, the UK releases use the NHS Digital TRUD API, and so you need to
pass in the following parameters:

- api-key   : your NHS Digital TRUD api key
- cache-dir : directory to use for downloading and caching releases 

For example,
```shell
clj -M:run --db snomed.db download uk.nhs/sct-clinical api-key xxx cache-dir /tmp/trud
clj -M:run --db snomed.db download uk.nhs/sct-drug-ext api-key xxx cache-dir /tmp/trud
```

`hermes` will tell you what configuration parameters are required:

```shell
clj -M:run download uk.nhs/sct-drug-ext
```
Will result in:
```
Invalid parameters for provider ' uk.nhs/sct-drug-ext ':

should contain keys: :api-key, :cache-dir

| key        | spec    |
|============+=========|
| :api-key   | string? |
|------------+---------|
| :cache-dir | string? |

```
So we know we need to pass in `api-key` and `cache-dir` as above.

##### ii) Download and install SNOMED CT distribution file(s) manually

Depending on where you live in the World, download the most appropriate
distribution(s) for your needs.

In the UK, we can obtain these from [TRUD](ttps://isd.digital.nhs.uk).

For example, you can download the UK "Clinical Edition", containing the International and UK clinical distributions 
as part of TRUD pack 26/subpack 101.

* [https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases](https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases)

Optionally, you can also download the UK SNOMED CT drug extension, that contains the dictionary of medicines and devices (dm+d) is available
as part of TRUD pack 26/subpack 105.

* [https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/105/releases](https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/105/releases)

Once you have downloaded what you need, unzip them to a common directory and 
then you can use `hermes` to create a file-based database. 

If you are running using the jar file:

```shell
java -jar hermes.jar --db snomed.db import ~/Downloads/snomed-2020
```

If you are running from source code:

```shell
clj -M:run --db snomed.db import ~/Downloads/snomed-2020/
```

The import of both International and UK distribution files takes
a total of less than 15 minutes on my 8 year old laptop.

#### 2. Compact database (optional).

This reduces the file size by around 20% and takes about 1 minute.
This is an optional step. 

```shell
java -jar hermes.jar --db snomed.db compact
```

or

```shell
clj -M:run --db snomed.db compact
```

#### 3. Build search index

Run 
```shell
java -jar hermes.jar --db snomed.db index
```

or

```
clj -M:run --db snomed.db index
```
This will build the search index; it takes about 5 minutes.

#### 4. Run a REPL (optional)

When I first built terminology tools, either in java or in golang, I needed to
also build a custom command-line interface in order to explore the ontology.
This is not necessary as most developers using Clojure quickly learn the value
of the REPL; a read-evaluate-print-loop in which one can issue arbitrary 
commands to execute. As such, one has a full Turing-complete language (a lisp)
in which to explore the domain. 

Run a REPL and use the terminology services interactively.

```
clj -A:dev
```

#### 5. Run a terminology web service

```
java -jar hermes.jar --db snomed.db --port 8080 serve 
```

or
```
clj -M:run --db snomed.db --port 8080 serve
```

Example usage of search endpoint:
```shell
curl "http://localhost:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5" -H "Accept: application/json"  | jq
```

Results:
```shell.
[
  {
    "id": 486696014,
    "conceptId": 37340000,
    "term": "MND - Motor neurone disease",
    "preferredTerm": "Motor neuron disease"
  }
]

```

Further documentation will follow. 

There are endpoints for crossmapping to and from SNOMED, as well as obtaining
an extended concept with much of the information required for rapid inference.

#### 6. Embed into another application

In your `deps.edn` file (make sure you change the commit-id):
```
[com.eldrix.hermes {:git/url "https://github.com/wardle/hermes.git"
                    :sha     "097e3094070587dc9362ca4564401a924bea952c"}
``` 

Or, build a library jar (see below)

### B. How to use a running service in your own applications

The terminology server can be embedded into your own applications or, more
commonly, you would use as a standalone web service. Further documentation
will follow.

### C. How to run or build from source code

#### Run compilation checks (optional)

```
clj -M:check
```

#### Run unit tests and linters (optional)

```
clj -M:test
clj -M:lint/kondo
clj -M:lint/eastwood
```

#### Run direct from the command-line

You will get help text.

```
clj -M -m com.eldrix.hermes.core
```

#### View outdated dependencies

```
clj -M:outdated
```

You can view a complete list of dependencies; try:

```
clj -X:deps tree
```

#### Building uberjar

Create a POM from the dependencies 
(or create manually - changing the groupId, artifactId and version):
```
clj -Spom
```
Build the uberjar:
```
clojure -X:uberjar
```

#### Building library jar

A library jar contains only hermes-code, and none of the bundled dependencies.  

```
clojure -X:jar
```

