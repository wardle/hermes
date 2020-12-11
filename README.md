# Hermes : terminology tools and services

>
> Hermes:  "Herald of the gods."
>

[![Scc Count Badge](https://sloc.xyz/github/wardle/hermes)](https://github.com/wardle/hermes/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hermes?category=cocomo)](https://github.com/wardle/hermes/)

Hermes provides a set of terminology tools built around SNOMED CT including:

* a fast RESTful terminology server with full-text search functionality; ideal for driving autocompletion in user interfaces
* an inference engine in order to analyse SNOMED CT expressions and concepts and derive meaning
* cross-mapping to and from other code systems

It is designed as both library for embedding into larger applications, or as a microservice. 

It replaces previous similar tools written in java and golang and is designed to fit into a wider architecture
with identifier resolution, mapping and semantics as first-class abstractions.

### A. How to download and build a terminology service

Ensure you have a pre-built jar file, or the source code checked out from github. See below for build instructions.

#### 1. Download SNOMED CT distribution file(s). 

In the UK, we can obtain these from [TRUD](ttps://isd.digital.nhs.uk). 

For example, you can download the UK "Clinical Edition", containing the International and UK clinical distributions 
as part of TRUD pack 26/subpack 101.

* [https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases](https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases)

Optionally, you can also download the UK SNOMED CT drug extension, that contains the dictionary of medicines and devices (dm+d) is available
as part of TRUD pack 26/subpack 101.

* [https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/105/releases](https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/105/releases)

Depending on where you live in the World, download the most appropriate 
distribution(s) for your needs.

#### 2. Import 

Once you have downloaded what you need, unzip them to a common directory and 
then you can use `hermes` to create a file-based database. 

If you are running using the jar file:

```
java -jar hermes.jar -d snomed.db import ~/Downloads/snomed-2020
```

If you are running from source code:
```
clj -m com.eldrix.hermes.core -d snomed.db import ~/Downloads/snomed-2020/
```

The import of distribution files takes about 50 minutes on my 8 year old laptop.

#### 3. Build indexes

Run 
```
clj -m com.eldrix.hermes.core -d snomed.db index
```
This will:
* Build entity indices (about 15 minutes)
* Build search index (about 10 minutes)

#### 4. Compact database (optional).

This reduces the file size by around 20%. 
It takes considerable memory (heap) to do this although it takes only 3 minutes.

```
clj -J-X-mx8g com.eldrix.hermes.core -d snomed.db compact
```

You will usually need to increase the heap space (by using -Xmx8g) in order to
complete the compaction step which will reduce the database size by up to 20%.

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

#### 4. Run a terminology web service

```
java -jar hermes.jar -d snomed.db -p 8080 serve 
```

or
```
clj -M -m com.eldrix.hermes.core -d snomed.db -p 8080 serve
```

#### 5. Embed into another application

In your `deps.edn` file (make sure you change the commit-id):
```
[com.eldrix.hermes {:git/url "https://github.com/wardle/hermes.git"
                    :sha     "097e3094070587dc9362ca4564401a924bea952c"}
``` 

### B. How to use a running service in your own applications

The terminology server can be embedded into your own applications or, more
commonly, you would use as a standalone web service. Further documentation
will follow.

### C. How to run or build from source code

#### Run compilation checks (optional)

```
clj -M:check
```

#### Run unit tests (optional)

```
clj -M:test
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
clj -Spom``
```
Build the uberjar:
```
clojure -X:uberjar
```
