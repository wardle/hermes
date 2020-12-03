# Hermes : intelligent terminology tools

`hermes` is a set terminology tools including a fast server with full-text search functionality.

It is designed as both library for embedding into larger applications, or as a microservice. 

It replaces previous similar tools written in java and golang and is designed to fit into a wider architecture
with identifier resolution, mapping and semantics as first-class abstractions.

### How to use

Ensure you have a pre-built jar file, or the source code checked out from github.

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
java -jar hermes.jar import snomed.db ~/Downloads/snomed-2020
```

If you are running from source code:
```
clj -m com.eldrix.hermes.core import snomed.db ~/Downloads/snomed-2020/
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

#### 4. Compact database (optional)

This reduces the file size by around 20%. It takes considerable heap to do this.

```
clj -m com.eldrix.hermes.core -d snomed.db compact
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
java -jar hermes.jar --db snomed.db server --port 8080
```

or
```
clj -M -m com.eldrix.hermes.core --db snomed.db server --port 8080
```

#### 5. Embed into another application

In your `deps.edn` file (make sure you change the commit-id):
```
[com.eldrix.hermes {:git/url "https://github.com/wardle/hermes.git"
                    :sha     "097e3094070587dc9362ca4564401a924bea952c"}
``` 

### Running or building from source code

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
clj -m com.eldrix.hermes.core
```

#### View outdated dependencies

```
clj -M:outdated
```

#### Building uberjar

Perform ahead-of-time (AOT) compilation (see [https://clojure.org/guides/deps_and_cli#aot_compilation](https://clojure.org/guides/deps_and_cli#aot_compilation))
```
clj -M -e "(compile 'com.eldrix.hermes.core)"
```

This will generate class files to create a runnable jar. Now you can build an uberjar:

```
clojure -M:uberjar
```