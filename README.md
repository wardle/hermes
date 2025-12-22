# Hermes : terminology tools, library and microservice.

[![Scc Count Badge](https://sloc.xyz/github/wardle/hermes)](https://github.com/wardle/hermes/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hermes?category=cocomo&avg-wage=100000)](https://github.com/wardle/hermes/)
[![Test](https://github.com/wardle/hermes/actions/workflows/test.yml/badge.svg)](https://github.com/wardle/hermes/actions/workflows/test.yml)
[![DOI](https://zenodo.org/badge/293230222.svg)](https://zenodo.org/badge/latestdoi/293230222)
<br/>
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/wardle/hermes)](https://github.com/wardle/hermes/releases/latest)
[![Clojars Project](https://img.shields.io/clojars/v/com.eldrix/hermes.svg)](https://clojars.org/com.eldrix/hermes)
[![cljdoc badge](https://cljdoc.org/badge/com.eldrix/hermes)](https://cljdoc.org/d/com.eldrix/hermes)

Hermes provides a set of terminology tools built around SNOMED CT including:

* a fast terminology service with full-text search functionality; ideal for driving autocompletion in user interfaces
* an inference engine in order to analyse SNOMED CT expressions and concepts and derive meaning
* cross-mapping to and from other code systems including ICD-10, Read codes and OPCS
* support for SNOMED CT compositional grammar (cg) and expression constraint language (ECL) v2.2.
* optional HL7 FHIR terminology server via [hades](https://github.com/wardle/hades)

It is designed as both a library for embedding into larger applications and as a standalone microservice.

It is fast, both for import and for use. It imports and indexes the International
and UK editions of SNOMED CT in less than 5 minutes; you can have a server
running seconds after that.

It replaces previous similar tools I wrote in Java and Go and is designed to fit into a wider architecture with
identifier resolution, mapping and semantics as first-class abstractions.

Rather than a single monolithic terminology server, it is entirely reasonable to build
multiple services, each providing an API around a specific edition or version of SNOMED CT,
and to use an API gateway to manage client access. `Hermes` is lightweight and designed
to be composed with other services.

It is part of my PatientCare v4 development; previous versions have been operational within NHS Wales
since 2007.

You can have a working terminology server running by typing only a few lines at a terminal. There's no need
for any special hardware, or any special dependencies such as setting up your own elasticsearch or solr cluster.
You just need a filesystem! Many other tools take hours to import the SNOMED data; you'll be finished in less than
10 minutes!

A HL7 FHIR terminology facade is under development : [hades](https://github.com/wardle/hades).
This exposes the functionality available in `hermes` via a FHIR terminology API. This already
supports search and autocompletion using the $expand operation.

# Table of contents

- [Quickstart](#quickstart)
- [Common questions](#common-questions)
  - [What can I do with `hermes`?](#what-can-i-do-with-hermes)
  - [Difference to a 'national terminology server'?](#how-is-this-different-to-a-national-terminology-service)
  - [Localisation](#localisation)
  - [Can I get support?](#can-i-get-support)
  - [Why are you building so many repositories?](#why-are-you-building-so-many-repositories)
  - [What are you using `hermes` for?](#what-are-you-using-hermes-for)
  - [What is this graph stuff you're doing?](#what-is-this-graph-stuff-youre-doing)
  - [Is `hermes` fast?](#is-hermes-fast)
  - [Can I use `hermes` with containers?](#can-i-use-hermes-with-containers)
  - [Can I use `hermes` on Apple Silicon?](#can-i-use-hermes-on-apple-silicon)
  - [Can I use `hermes` on other architectures or operating systems such as FreeBSD?](#can-i-use-hermes-on-other-architectures-or-operating-systems-such-as-freebsd)
- [Documentation](#documentation)
  - [A. Download and build a terminology service](#a-how-to-download-and-build-a-terminology-service)
    - [1. Download and install at least one distribution](#1-download-and-install-at-least-one-distribution)
    - [2. Index](#2-index)
    - [3. Compact (optional)](#3-compact-database-optional)
    - [4. Run a REPL (optional)](#4-run-a-repl-optional)
    - [5. Get status (optional)](#5-get-the-status-of-your-installed-index)
    - [6. Run a HTTP terminology server](#6-run-a-terminology-web-service)
    - [7. Run a FHIR terminology server](#7-run-a-hl7-fhir-terminology-web-service)
  - [B. Endpoints for the HTTP server](#b-endpoints-for-the-http-terminology-server)
    - [Get a single concept](#get-a-single-concept-)
    - [Get extended information about a single concept](#get-extended-information-about-a-single-concept)
    - [Get preferred synonym](#get-preferred-synonym)
    - [Get properties for a single concept](#get-properties-for-a-single-concept)
    - [Search](#search)
    - [Expanding SNOMED ECL (Expression Constraint Language)](#expanding-ecl-without-search)
    - [Crossmap to and from SNOMED CT - e.g. ICD-10](#crossmap-to-and-from-snomed-ct)
    - [Map a concept into a reference set](#map-a-concept-into-a-reference-set)
  - [C. Embed into another application as a JVM library](#c-embed-into-another-application)
  - [D. Development notes](#d-development)
  - [E. Backwards compatibility and versioning](#e-backwards-compatibility-and-versioning)

# Quickstart

You can have a terminology server running in minutes.
Full documentation is below, but here is a quickstart.

Before you begin, you will need to have Java version 17 or greater installed. 

### 1. Download hermes

You can choose to run a jar file by downloading a release and running using Java, 
or run from source code using Clojure:

##### Download a release and run using Java

Download the latest release from https://github.com/wardle/hermes/releases
For simplicity, I've renamed the download jar file to 'hermes.jar' for these
examples

Run the jar file using:

```shell
java -jar hermes.jar
```

When run without parameters, you will be given help text.

In all examples below, `java -jar hermes.jar` is equivalent to `clj -M:run` and vice versa.

##### Run from source code using Clojure

Install Clojure. e.g on Mac OS X:

```shell
brew install clojure
```

Then clone the repository, change directory and run:

```shell
git clone https://github.com/wardle/hermes
cd hermes
clj -M:run
```

When run without parameters, you will be given help text.

In all examples below, `java -jar hermes.jar` is equivalent to `clj -M:run` and vice versa.

### 2. Download and install one or more distributions

You will need to download distributions from a National Release Centre.

How to do this will principally depend on your location. 

For more information, see [https://www.snomed.org/snomed-ct/get-snomed](https://www.snomed.org/snomed-ct/get-snomed).
SNOMED provide a [Member Licensing and Distribution Centre](https://mlds.ihtsdotools.org/#/landing).

In the United States, the National Library of Medicine (NLM) has [more information](https://www.nlm.nih.gov/healthit/snomedct/snomed_licensing.html). For example, the SNOMED USA edition is available from [https://www.nlm.nih.gov/healthit/snomedct/us_edition.html](https://www.nlm.nih.gov/healthit/snomedct/us_edition.html).

In the United Kingdom, you can download a distribution from NHS Digital using the [TRUD service](https://isd.digital.nhs.uk).

`hermes` also provides automated downloads for a range of distributions worldwide using the [MLDS](https://mlds.ihtsdotools.org).

If you've downloaded a distribution manually, unzip and import using one of these commands:

```shell
java -jar hermes.jar --db snomed.db import ~/Downloads/snomed-2021/
```
or
```shell
clj -M:run --db snomed.db import ~/Downloads/snomed-2021/
```

If you're a UK user and want to use automatic downloads, you can do this

```shell
java -jar hermes.jar --db snomed.db install --dist uk.nhs/sct-clinical --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud
```
```shell
clj -M:run --db snomed.db install --dist uk.nhs/sct-clinical --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud
```

Ensure you have a [TRUD API key](https://isd.digital.nhs.uk/trud3/user/guest/group/0/home).

This will download both the UK clinical edition and the UK drug extension. If you're a UK user, I'd recommend installing both.

When running interactively at the command-line, you can use `--progress` to turn on 
progress reporting when downloading items.

e.g.
```shell
java -jar hermes.jar --progress --db snomed.db install --dist uk.nhs/sct-clinical --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud
```
```shell
clj -M:run --progress --db snomed.db install --dist uk.nhs/sct-clinical --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud
```


You can download a specific edition using an ISO 6801 formatted date:


```shell
java -jar hermes.jar --db snomed.db install --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud --release-date 2021-03-24
java -jar hermes.jar --db snomed.db install --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud --release-date 2021-03-24
```
or
```shell
clj -M:run --db snomed.db install --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud --release-date 2021-03-24
clj -M:run --db snomed.db install --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt --cache-dir /tmp/trud --release-date 2021-03-24
```

These are most useful for building reproducible container images.
You can get a list of available UK versions by simply looking at the TRUD website, or using:

```shell
java -jar hermes.jar available --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud
```
or
```shell
clj -M:run available --dist uk.nhs/sct-clinical --api-key trud-api-key.txt --cache-dir /tmp/trud
```


My tiny i5 'NUC' machine takes 1 minute to import the UK edition of SNOMED CT and a further minute to import the UK
dictionary
of medicines and devices.

If you have an account with the [MLDS](https://mlds.ihtsdotools.org), then you can use that website to download
a distribution manually, or `hermes` can do it for you. 

```shell
java -jar hermes.jar available
```
or
```shell
clj -M:run available
```

For example, to install the Irish distribution:

```shell
java -jar hermes.jar --db snomed.db install --dist ie.mlds/285520 --username xxxx --password password.txt
```
or 
```shell
clj -M:run --db snomed.db install --dist ie.mlds/285520 --username xxxx --password password.txt
```

You can request a specific version by providing `--release-date` as an option.
You will need to have a licence for the distribution you are trying to download,
or you will get an 'invalid credentials' error. 


### 3. Index and compact

You must index. Compaction is not mandatory, but advisable.

```shell
java -jar hermes.jar --db snomed.db index compact
```
or
```shell
clj -M:run --db snomed.db index compact
```

My machine takes 6 minutes to build the search indices and 20 seconds to compact the database.

### 4. Run a server!

```shell
java -jar hermes.jar --db snomed.db --port 8080 --bind-address 0.0.0.0 serve
```
or
```shell
clj -M:run --db snomed.db --port 8080 serve
```

You can use [hades](https://github.com/wardle/hades) with the 'snomed.db' index
to give you a FHIR terminology server.

More detailed documentation is included below.

You can use multiple commands at the same time.

For example:
```shell
java -jar hermes.jar --api-key trud-api-key.txt --db snomed.db install uk.nhs/sct-clinical index compact serve 
```
Will download, extract, import, index and compact a database, and then run a server.


# Common questions

### What can I do with `hermes`?

`hermes` provides a simple library, and optionally a microservice, to help
you make use of SNOMED CT.

A library can be embedded into your application; this is easy using Clojure or
Java or any other language running on the JVM. You make calls using the API just 
as you'd use any regular library. 

A microservice runs independently and you make use of the data and software
by making an API call over the network. This makes the functionality available
to any software code that can use HTTP and JSON, such as C#, Python or R.

Like all `PatientCare` components, you can use `hermes` in either way.
Sometimes, when you're starting out, it's best to use as a library but larger
projects and larger installations will want to run their software components
independently, optimising for usage patterns, resilience, reliability and
rate of change.

Most people who use a terminology run a server and make calls over the network.

### How is this different to a national terminology service?

Previously, I implemented SNOMED CT within an EPR.
Later I realised how important it was to build it as a separate module;
I created terminology servers in Java, and then later in Go;
`hermes` is written in Clojure. In the UK,  the different health services in England
and Wales have procured a centralised national terminology server. 
While I support the provision of a national terminology server for convenience, 
I think it's important to recognise that it is the *data* that matters most. We 
need to cooperate and collaborate on semantic interoperability, but the software 
services that make use of those data can be centralised or distributed; when I 
do analytics, I can't see me making server round-trips for every check of 
subsumption! That would be silly; I've been using SNOMED for analytics for 
longer than most; you need flexibility in provisioning terminology services. I 
want tooling that can both provide services at scale, while is capable of 
running on my personal computers as well. 

Unlike other available terminology servers, `hermes` is lightweight and has no 
other dependencies except a filesystem, which can be read-only when in 
operation. This makes it ideal for use in situations such as a data pipeline, 
perhaps built upon Apache Kafka - with `hermes`, SNOMED analytics capability can 
be embedded anywhere. 

I don't believe in the idea of uploading codesystems and value sets in place.
My approach to versioning is to run different services; I simply deploy new
services and switch at the API gateway level.

### Localisation

SNOMED CT is distributed across the world. The base distribution is the
International release, but your local distribution will include this together
with local data. Local data will include region-specific language reference
sets.

The core SNOMED API relating to concepts and their meaning is not affected
by issues of locale. Locale is used to derive the synonyms for any given
concept. There should be a single preferred synonym for every concept in a
given language reference set. 

When you build a database, the search index caches the preferred synonyms using
the installed locales.

### Can I get support?

Yes. Raise an issue, or more formal support options are available on request,
including a fully-managed service.

### Why are you building so many repositories?

Yes, I have a lot of repositories at [https://github.com/wardle](https://github.com/wardle),
providing functionality such as:

- Integration with UK NHS services via [concierge](https://github.com/wardle/concierge)
- UK dictionary of medicines and devices via [dmd](https://github.com/wardle/dmd)
- socioeconomic deprivation data via [deprivare](https://github.com/wardle/deprivare)
- UK reference data updates via [trud](https://github.com/wardle/trud)
- UK organisational data via [clods](https://github.com/wardle/clods)
- UK geographical data via the NHS postcode directory [nhspd](https://github.com/wardle/nhspd)

- I previously built an electronic patient record as a monolithic application with
many of these subsystems as modules of that larger system. Over time, I'm
splitting them out into their own more independent modules. 

I see the future of building health and care applications as simply composing
together different modules of core well-tested functionality to solve user 
problems. 

Small modules of functionality are easier to develop, easier to understand,
easier to test and easier to maintain. I design modules to be composable so that I can
stitch different components together in order to solve problems.

In larger systems, it is easy to see code rotting. Dependencies become outdated and
the software becomes difficult to change easily because of software that depend
on it. Small, well-defined modules are much easier to build and are less likely
to need ongoing changes over time; my goal is to need to update modules only
in response to changes in *domain* not software itself.
I aim for an accretion of functionality.

It is very difficult to 'prove' software is working as designed when there are
lots of moving parts.

### What are you using `hermes` for?

I have embedded it into clinical systems; I use it for a fast autocompletion
service so users start typing and the diagnosis, or procedure, or occupation,
or ethnicity, or whatever, pops up. Users don't generally know they're using
SNOMED CT. I use it to populate pop-ups and drop-down controls, and I use it
for decision support to switch functionality on and off in my user interface -
e.g. does this patient have a type of 'x' such as motor neurone disease - as well
as analytics. A large number of my academic publications are as a result of
using SNOMED in analytics.

### What is this graph stuff you're doing?

I think health and care data are and always will be heterogeneous, incomplete and difficult to process.
I do not think trying to build entities or classes representing our domain works at scale; it is
fine for toy applications and trivial data modelling such as e-observations, but classes and
object-orientation cannot scale across such a complete and disparate environment. Instead, I
find it much easier to think about first-class properties - entity - attribute - value - and
use such triples as a way of building and navigating a complex, hierarchical graph.

I am using a graph API in order to decouple subsystems and can now navigate from clinical data
into different types of reference data seamlessly. For example, with the same backend data, I can view
an x.500 representation of a practitioner, or a FHIR R4 Practitioner resource model.
The key is to recognise that identifier resolution and mapping are first class problems within
the health and care domain. Similarly, I think the semantics of reading data are very different to
one of writing data. I cannot shoehorn health and care data into a REST model in which we read and write
to resources representing the type. Instead, just as in real-life, we record event data which can effect change.
In the end, it is all data.

### Is `hermes` fast?

Hermes benefits from the speed of the libraries it uses, particularly [Apache Lucene](https://lucene.apache.org)
and [lmdb](https://www.symas.com/lmdb), and from some fundamental design
decisions including read-only operation and memory-mapped data files. It provides a
HTTP server using the lightweight and reliable [jetty web server](https://www.eclipse.org/jetty/).

I have a small i3 NUC server on my local wifi network, and here is an example of
load testing, in which users are typing 'mnd' and expecting an autocompletion:

```shell
mark@jupiter classes % wrk -c300 -t12 -d30s --latency  'http://nuc:8080/v1/snomed/search?s=mnd'
Running 30s test @ http://nuc:8080/v1/snomed/search?s=mnd
  12 threads and 300 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    40.36ms   19.97ms 565.73ms   92.08%
    Req/Sec   632.19     66.79     0.85k    68.70%
  Latency Distribution
     50%   38.76ms
     75%   45.93ms
     90%   54.09ms
     99%   79.31ms
  226942 requests in 30.09s, 125.75MB read
Requests/sec:   7540.91
Transfer/sec:      4.18MB
```

This uses 12 threads to make 300 concurrent HTTP connections.
On 99% of occasions, that would provide a fast enough response for
autocompletion (<79ms). Of course, that is users typing at exactly the same time,
so a single instance could support more concurrent users than that. Given its design, Hermes is designed to easily scale
horizontally, because you can simply run more servers and load balance across
them. Of course, these data are fairly crude, because in real-life you'll be
doing more complex concurrent calls. In real deployments, I've only needed one instance for hundreds of concurrent
users, but it is nice to know I can scale easily.

### Can I use `hermes` with containers?

Yes. It is designed to be containerised, although I have a mixture of different
approaches in production, including running from source code directly. I would
usually advise creating a volume and populating that with data, and then
permitting read-only access to your service containers. A shared volume can be
memory mapped by multiple running instances and provide high scalability.

There are some examples of [different configurations available](https://github.com/wardle/hermes-docker).

### Can I use `hermes` on Apple Silicon? 

Yes. There are three options. 

The first is to use Rosetta and run an x86 Java SDK and this will look for an x86 LMDB library already bundled with `hermes`.

The other two options install a native aarch64 LMDB library, and make it available to
`hermes`. The best performance will be gained from using a native library.

The next version of `lmdbjava` will include a pre-built lmdb binary for ARM on
Mac OS X, so these steps will become unnecessary and `hermes` will work on 
multiple architectures and operating systems without needing these steps.  

##### Option 1. Install an x86 Java SDK and run using that (Rosetta). 

For example, you can get a list of installed JDKs:
```shell
$ /usr/libexec/java_home -V

    11.0.17 (arm64) "Amazon.com Inc." - "Amazon Corretto 11" /Users/mark/Library/Java/JavaVirtualMachines/corretto-11.0.17/Contents/Home
    11.0.14.1 (x86_64) "Azul Systems, Inc." - "Zulu 11.54.25" /Users/mark/Library/Java/JavaVirtualMachines/azul-11.0.14.1-1--x86/Contents/Home
```

Choose an SDK and check what we are using
```shell
$ export JAVA_HOME=$(/usr/libexec/java_home -v 11.0.14.1)
$ clj -M -e '(System/getProperty "os.arch")'

"x86_64"

```
##### Option 2. Install the lmdb library for your architecture

Here I use homebrew on my mac:

```shell
brew install lmdb
brew list lmdb
```

Once you have a native LMDB installed on your machine, you can reference it 
from the command line:

```shell
java -Dlmdbjava.native.lib=/opt/homebrew/Cellar/lmdb/0.9.30/lib/liblmdb.dylib -jar target/hermes-1.2.1151.jar --db snomed.db status
```
or
```shell
clj -J-Dlmdbjava.native.lib=/opt/homebrew/Cellar/lmdb/0.9.30/lib/liblmdb.dylib -M:run --db snomed.db status
```

##### Option 3. Build the lmdb library for your architecture (ie arm64).

Install the xcode command line tools, if they are not already installed
```shell
xcode-select --install
```

And then download lmdb and build:
```shell
git clone --depth 1 https://git.openldap.org/openldap/openldap.git
cd openldap/libraries/liblmdb
make -e SOEXT=.dylib
mkdir -p ~/Library/Java/Extensions
cp liblmdb.dylib ~/Library/Java/Extensions
```

In this example, rather than specifying the location of the library at the 
command line, I'm just copying the library to a well known location.

Once this native library is copied, you can use `hermes` natively using an arm64 based JDK.

```shell
$ export JAVA_HOME=$(/usr/libexec/java_home -v 11.0.17)
$ clj -M -e '(System/getProperty "os.arch")'

"aarch64"
```

### Can I use `hermes` on other architectures or operating systems such as FreeBSD?

If `hermes` does not already contain a pre-built binary for your operating system and architecture,
you simply need to install lmdb yourself. You may need to also tell `hermes` where to 
find the native library.

e.g. on FreeBSD:

```shell
$ pkg info -lx lmdb | grep liblmdb

	/usr/local/lib/liblmdb.a
	/usr/local/lib/liblmdb.so
	/usr/local/lib/liblmdb.so.0
```

```shell
java -Dlmdbjava.native.lib=/usr/local/lib/liblmdb.so -jar target/hermes-1.2.1151.jar --db snomed.db status
```
or
```shell
clj -J-Dlmdbjava.native.lib=/usr/local/lib/liblmdb.so -M:run --db snomed.db status
```


# Documentation

### A. How to download and build a terminology service

Ensure you have a pre-built jar file, or the source code checked out from github. See below for build instructions.

I'd recommend installing clojure and running using source code but use the pre-built jar file if you prefer.

#### 1. Download and install at least one distribution.

If your local distributor is supported, `hermes` can do this automatically for you.
Otherwise, you will need to download your local distribution(s) manually.

##### i) Use a registered SNOMED CT distributor to automatically download and import

You can see distributions that are available for automatic installation:

```shell
java -jar hermes.jar available
```

```shell
clj -M:run available
```

The basic command is:

```shell
clj -M:run --db snomed.db install --dist <distribution-identifier> [properties] 
```

or if you are using a precompiled jar:

```shell
java -jar hermes.jar --db snomed.db install --dist <distribution-identifier> [properties]
```

The distribution, as defined by `distribution-identifier`, will be downloaded
and imported to the file-based database `snomed.db`.

| Distribution-identifier | Description                                        |
|-------------------------|----------------------------------------------------|
| uk.nhs/sct-clinical     | UK SNOMED CT clinical - incl international release |
| uk.nhs/sct-drug-ext     | UK SNOMED CT drug extension - incl dm+d            |
| uk.nhs/sct-monolith     | UK SNOMED CT monolith edition: includes everything |

At the time of writing, the UK monolith edition is labelled as `Draft for Trial Use`.

Each distribution might require custom configuration options. 

For example, the UK releases use the NHS Digital TRUD API, and so you need to
pass in the following parameters:

- `--api-key`   : path to a file containing your NHS Digital TRUD api key
- `--cache-dir` : directory to use for downloading and caching releases

For example, these commands will download, cache and install the International
release, the UK clinical edition and the UK drug extension:

```shell
clj -M:run --db snomed.db install uk.nhs/sct-monolith --api-key=trud-api-key.txt --cache-dir=/tmp/trud
```

`hermes` will tell you what configuration parameters are required:

```shell
java -jar hermes.jar install --dist uk.nhs/sct-clinical --help
```
or
```shell
clj -M:run install --dist uk.nhs/sct-clinical --help
```

For the UK, TRUD requires an `--api-key`, which should be a path to a file 
containing your API key for that service.

You will need to provide different configuration options if `hermes`
is using the MLDS to download distributions:

```shell
java -jar hermes.jar install --dist nl.mlds/128785 --help
```
or
```shell
clj -M:run install --dist nl.mlds/128785 --help
```

For MLDS downloads, you will need to provide `--username` and `--password` options. 
The password should be the path to a file containing your password. This makes
it safer to use in automated pipelines and less likely to be accidentally logged.

##### ii) Download and install SNOMED CT distribution file(s) manually

Depending on where you live in the World, download the most appropriate
distribution(s) for your needs.

In the UK, we can obtain these from [TRUD](https://isd.digital.nhs.uk).

For example, you can download the UK "Clinical Edition", containing the International and UK clinical distributions
as part of TRUD pack 26/subpack 101.

* [https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases](https://isd.digital.nhs.uk/trud3/user/authenticated/group/0/pack/26/subpack/101/releases)

Optionally, you can also download the UK SNOMED CT drug extension, that contains the dictionary of medicines and
devices (dm+d) is available
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
a total of less than 3 minutes on my machine.


#### 2. Index

For correct operation, indices are needed for components, search and reference
set membership.

Run

```shell
java -jar hermes.jar --db snomed.db index
```

or

```shell
clj -M:run --db snomed.db index
```

This will build the indices; it takes about 6 minutes on my machine.

#### 3. Compact database (optional).

This reduces the file size and takes 20 seconds.
This is an optional step, but recommended.

```shell
java -jar hermes.jar --db snomed.db compact
```

or

```shell
clj -M:run --db snomed.db compact
```

#### 4. Run a REPL (optional)

When I first built terminology tools, either in Java or in Go, I needed to
also build a custom command-line interface in order to explore the ontology.
This is not necessary as most developers using Clojure quickly learn the value
of the REPL; a read-evaluate-print-loop in which one can issue arbitrary
commands to execute. As such, one has a full Turing-complete language (a lisp)
in which to explore the domain.

I usually use a REPL from within my IDE so run a REPL from there.
You can run an nREPL server, which makes it easy to connect from other editors, such as emacs or neovim:

```
clj -M:dev:nrepl-server
```

You can run a REPL and use the terminology services interactively at the command-line, but I
would not advise this. It is much better to use a REPL within your editor.

```
clj -M:dev
```

#### 5. Get the status of your installed index

You can obtain status information about any index by using:

```shell
clj -M:run --db snomed.db status --format json
```

Result:

```json
{"releases":
["SNOMED Clinical Terms version: 20220731 [R] (July 2022 Release)",
  "35.6.0_20230315000001 UK drug extension",
  "35.6.0_20230315000001 UK clinical extension"],
  "locales":["en-GB", "en-US"],
  "components":
  {"concepts":1068735,
    "descriptions":3050621,
    "relationships":7956235,
    "concrete-values":33349,
    "refsets":541,
    "refset-items":13349472,
    "indices":
    {"descriptions-concept":3050621,
      "concept-parent-relationships":4737884,
      "concept-child-relationships":4737884,
      "component-refsets":10595249,
      "associations":1254384,
      "descriptions-search":3050621,
      "members-search":13349472}}}
```
In this example, you can see I have the July 2022 International release, with the 
UK clinical and drug extensions from March 2023.
Given that these releases have been imported, hermes recognises it can support
the locales en-GB and en-US. For completeness, detailed statistics on 
components and indices are also provided. Additional options are available:

```shell
java -jar hermes.jar --db snomed.db status --help
```
or
```shell
clj -M:run --db snomed.db status --help
```

#### 6. Run a terminology web service

By default, data are returned using json, but you can
request [edn](https://github.com/edn-format/edn) by simply adding "Accept:application/edn" in the request header.

```
java -jar hermes.jar --db snomed.db --port 8080 --bind-address 0.0.0.0 serve 
```

or

```
clj -M:run --db snomed.db --port 8080 --bind-address 0.0.0.0 serve
```

There are a number of configuration options for `serve`:

```shell
java -jar hermes.jar serve --help
```
or
```shell
clj -M:run serve --help
```

```shell
Usage: hermes [options] serve

Start a terminology server

Options:
      --allowed-origin "*" or ORIGIN    []     Set CORS policy, with "*" or hostname
      --allowed-origins "*" or ORIGINS         Set CORS policy, with "*" or comma-delimited hostnames
  -a, --bind-address BIND_ADDRESS              Address to bind
  -d, --db PATH                                Path to database directory
  -h, --help
      --locale LOCALE                   en-GB  Set default / fallback locale
  -p, --port PORT                       8080   Port number
```

* `--bind-address` is optional. You may want to use `--bind-address 0.0.0.0` particularly if you are running in a container.
* `--allowed-origins` is optional. You could use `--allowed-origins "*"` or `--allowed-origins example.com,example.net`
* `--allowed-origin example.com --allowed-origin example.net` is equivalent to `--allowed-origins example.com,example.net`.
* `--allowed-origin "*"` is equivalent to `--allowed-origins "*"`
* `--locale` sets the default locale. This is used as a default if clients do not specify their preference. e.g. `--locale=en-GB`

By default, the default locale will be determined by looking at which language reference sets are installed.

#### 7. Run a HL7 FHIR terminology web service

You can use [`hades`](https://github.com/wardle/hades) together with the 
files you have just created to run a FHIR R4 terminology server.

### B. Endpoints for the HTTP terminology server

There are a range of endpoints. 

I have a very small, low-powered server (<$3/mo) available for demonstration purposes. It is not intended for production use. 

Here are some examples:

* [/v1/snomed/concepts/24700007](http://128.140.5.148:8080/v1/snomed/concepts/24700007) - basic data about a single concept
* [/v1/snomed/concepts/24700007/descriptions](http://128.140.5.148:8080/v1/snomed/concepts/24700007/descriptions) - all descriptions for concept
* [/v1/snomed/concepts/24700007/preferred](http://128.140.5.148:8080/v1/snomed/concepts/24700007/preferred) : preferred description for concept. Use an `Accept-Language` header to choose your locale (see below).
* [/v1/snomed/concepts/24700007/extended](http://128.140.5.148:8080/v1/snomed/concepts/24700007/extended) : an extended concept 
* [/v1/snomed/concepts/1231295007/properties?expand=1](http://128.140.5.148:8080/v1/snomed/concepts/24700007/properties?expand=1) : properties for a concept
* [/v1/snomed/concepts/586591000000100/historical](http://128.140.5.148:8080/v1/snomed/concepts/586591000000100/historical) - historical associations for this concept
* [/v1/snomed/concepts/24700007/refsets](http://128.140.5.148:8080/v1/snomed/concepts/24700007/refsets) - refsets to which this concept is a member
* [/v1/snomed/concepts/24700007/map/999002271000000101](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101) - crossmap to alternate codesystem (ICD-10 in this example)
* [/v1/snomed/concepts/24700007/map/991411000000109](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/991411000000109) - map into a SNOMED subset (the UK emergency unit refset in this example)
* [/v1/snomed/crossmap/999002271000000101/G35X](http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X) - cross from an alternate codesystem (ICD-10 in this example)
* [/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5](http://128.140.5.148:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5) - search for a term, constrained by SNOMED ECL expression
* [/v1/snomed/expand?ecl= <19829001 AND <301867009&includeHistoric=true](http://128.140.5.148:8080/v1/snomed/expand?ecl=%20%3C19829001%20AND%20%3C301867009&includeHistoric=true) - expand SNOMED ECL expression

**WARNING**

The HTTP API returns data formatted as either JSON or EDN. Identifiers, such as concept or description identifiers, 
in SNOMED CT are [64-bit positive integers](https://confluence.ihtsdotools.org/display/DOCRELFMT/6+SNOMED+CT+Identifiers).
The JSON specification does not limit the size of numeric types, but some implementations struggle to properly manage
very large numbers and can silently truncate numbers. Most implementations have no such difficulty; if your client
library or platform does not properly handle large numbers in JSON, there is usually a way to configure your parser
to work correctly. For example, in JavaScript, you can use a [reviver parameter](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/JSON/parse#using_the_reviver_parameter).

Hermes could offer a per-server, or per-request configuration to stringify identifiers when output to JSON to help
broken client implementations. If this applies to you, please join the [discussion](https://github.com/wardle/hermes/issues/50).

##### Get a single concept 

```shell
http '127.0.0.1:8080/v1/snomed/concepts/24700007'
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

```edn

```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007](http://128.140.5.148:8080/v1/snomed/concepts/24700007)

You'll want to use the other endpoints much more frequently.

##### Get extended information about a single concept


```shell
http 127.0.0.1:8080/v1/snomed/concepts/24700007/extended
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007/extended](http://128.140.5.148:8080/v1/snomed/concepts/24700007/extended)

The result is an extended concept definition - all the information
needed for inference, logic and display. For example, at the client
level, we can then check whether this is a type of demyelinating disease
or is a disease affecting the central nervous system without further
server round-trips. Each relationship also includes the transitive closure tables for that
relationship, making it easier to execute logical inference.
Note how the list of descriptions includes a convenient
`acceptableIn` and `preferredIn` so you can easily display the preferred
term for your locale. If you provide an Accept-Language header, then
you will also get a preferredDescription that is the best choice for those
language preferences given what is installed.

```shell
HTTP/1.1 200 OK
Content-Type: application/json
Date: Mon, 08 Mar 2021 22:01:13 GMT

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
            "preferredIn": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "Multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2002-01-31",
            "id": 41399011,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [],
            "term": "Multiple sclerosis, NOS",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2015-01-31",
            "id": 41400016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [],
            "term": "Generalized multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2015-01-31",
            "id": 481990016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [],
            "term": "Generalised multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 754365011,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "Multiple sclerosis (disorder)",
            "typeId": 900000000000003001
        },
        {
            "acceptableIn": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 1223979019,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "Disseminated sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "active": true,
            "caseSignificanceId": 900000000000017005,
            "conceptId": 24700007,
            "effectiveTime": "2003-07-31",
            "id": 1223980016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "MS - Multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptableIn": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "active": true,
            "caseSignificanceId": 900000000000017005,
            "conceptId": 24700007,
            "effectiveTime": "2003-07-31",
            "id": 1223981017,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferredIn": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "DS - Disseminated sclerosis",
            "typeId": 900000000000013009
        }
    ],
    "directParentRelationships": {
        "116676008": [
            409774005,
            32693004
        ],
        "116680003": [
            6118003,
            414029004,
            39367000
        ],
        "363698007": [
            21483005
        ],
        "370135005": [
            769247005
        ]
    },
    "parentRelationships": {
        "116676008": [
            138875005,
            107669003,
            123037004,
            409774005,
            32693004,
            49755003,
            118956008
        ],
        "116680003": [
            6118003,
            138875005,
            404684003,
            123946008,
            118234003,
            128139000,
            23853001,
            246556002,
            363170005,
            64572001,
            118940003,
            414029004,
            362975008,
            363171009,
            39367000,
            80690008,
            362965005
        ],
        "363698007": [
            138875005,
            21483005,
            442083009,
            123037004,
            25087005,
            91689009,
            91723000
        ],
        "370135005": [
            138875005,
            769247005,
            308489006,
            303102005,
            281586009,
            362981000,
            719982003
        ]
    },
    "refsets": [
        991381000000107,
        999002271000000101,
        991411000000109,
        1127581000000103,
        1127601000000107,
        900000000000497000,
        447562003
    ]
}

```
##### Get preferred synonym

You can simply get the preferred synonym for a concept using this endpoint.

```shell 
http://127.0.0.1:8080/v1/snomed/concepts/80146002/preferred
```

Try it live [http://128.140.5.148:8080/v1/snomed/concepts/80146002/preferred](http://128.140.5.148:8080/v1/snomed/concepts/80146002/preferred)

But `hermes` can use a defined language reference set, or even a simple locale if you want:

```shell 
http 'http://127.0.0.1:8080/v1/snomed/concepts/80146002/preferred' Accept-Language:en-GB
```

```json
{
    "active": true,
    "caseSignificanceId": 900000000000448009,
    "conceptId": 80146002,
    "effectiveTime": "2017-07-31",
    "id": 132973012,
    "languageCode": "en",
    "moduleId": 900000000000207008,
    "term": "Appendicectomy",
    "typeId": 900000000000013009
}
```
And now let's pretend we're in the United States:

```shell
http 'http://127.0.0.1:8080/v1/snomed/concepts/80146002/preferred' Accept-Language:en-US
```

```json
{
    "active": true,
    "caseSignificanceId": 900000000000448009,
    "conceptId": 80146002,
    "effectiveTime": "2017-07-31",
    "id": 132967011,
    "languageCode": "en",
    "moduleId": 900000000000207008,
    "term": "Appendectomy",
    "typeId": 900000000000013009
}
```

##### Get properties for a single concept

Each concept within SNOMED CT is associated with relationships. You can use 
`hermes` to return these as groups of properties, including concrete values
when available.

Here we look at properties for the concept representing the anti-convulsant
lamotrigine:

```shell
http 'http://127.0.0.1:8080/v1/snomed/concepts/1231295007/properties'
```

Try it live [http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties](http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties)

Note that when results are not expanded, the metadata model is used to fix 
the cardinality of the values for the relationship in the context of the concept.

```json
{
    "0": {
        "1142139005": "#1",
        "116680003": [
            779653004
        ],
        "411116001": 385060002,
        "763032000": 732936001,
        "766939001": [
            773862006
        ]
    },
    "1": {
        "1142135004": "#250",
        "1142136003": "#1",
        "732943007": 387562000,
        "732945000": 258684004,
        "732947008": 732936001,
        "762949000": 387562000
    }
}
```

Available parameters

- `expand` - expand results to include transitive relationships (`true`/`false`/`1`/`0`)
- `format` - format results 
- `key-format` - format keys
- `value-format` - format values

For machine-interpretation, it is best to simply use `?expand=1` and process
identifiers appropriately. For human consumption, and for interactive use, 
properties can be pretty-printed using a variety of formatting options:

Each format can be one of 
- `id` the identifier
- `syn` synonym (language determined by Accept-Language header or system/index defaults)
- `id:syn` a string of identifier and synonym
- `[id:syn]` a vector of identifier and synonym
- `{id:syn}` a map of identifier to synonym

Example:

```shell
http 'http://127.0.0.1:8080/v1/snomed/concepts/1231295007/properties?expand=0&format=id:syn'
```

Try it live [http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties?expand=1&format=id:syn](http://128.140.5.148:8080/v1/snomed/concepts/1231295007/properties?expand=0&format=id:syn)

Note again how the models within SNOMED CT are used to determine the cardinality
of the returned relationships. A drug can have multiple roles, but has only
single 'count of base of active ingredient and 'manufactured dose form' 
properties.

```json
{
    "0": {
        "1142139005:Count of base of active ingredient": "#1",
        "116680003:Is a": [
            "779653004:Lamotrigine only product in oral dose form"
        ],
        "411116001:Has manufactured dose form": "385060002:Prolonged-release oral tablet",
        "763032000:Has unit of presentation": "732936001:Tablet",
        "766939001:Plays role": [
            "773862006:Anticonvulsant therapeutic role"
        ]
    },
    "1": {
        "1142135004:Has presentation strength numerator value": "#250",
        "1142136003:Has presentation strength denominator value": "#1",
        "732943007:Has BoSS": "387562000:Lamotrigine",
        "732945000:Has presentation strength numerator unit": "258684004:mg",
        "732947008:Has presentation strength denominator unit": "732936001:Tablet",
        "762949000:Has precise active ingredient": "387562000:Lamotrigine"
    }
}

```


##### Search

Example usage of search endpoint.

```shell
http '127.0.0.1:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5'
````

Try it live: [http://128.140.5.148:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5](http://128.140.5.148:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5)

```json
[
  {
    "id": 486696014,
    "conceptId": 37340000,
    "term": "MND - Motor neurone disease",
    "preferredTerm": "Motor neuron disease"
  }
]

```

This searches only active concepts, but both active and inactive descriptions, by default. This can be changed
per request. The defaults are sensible, because a user trying to find something with a now inactive synonym
such as 'Wegener's Granulomatosis' will be suprised that their search fails to return any results.

Search parameters:

* `s` - the text to search
* `constraint` - an ECL expression to constrain the search; I never use search without this
* `maxHits` - maximum number of hits
* `inactiveConcepts` - whether to search inactive concepts (default, `false`)
* `inactiveDescriptions` - whether to search inactive descriptions (default, `true`)
* `fuzzy` - whether to use fuzziness for search (default, `false`)
* `fallbackFuzzy` - whether to retry using a fuzziness factor if initial search returns no results (default, `false`)
* `removeDuplicates` - whether to remove consecutive results with the same conceptId and text (default, `false`)
* `showFsn` - whether to show the fully specified name in the results (default, `false`)

For autocompletion, in a typical type-ahead user interface control, you might use `fallbackFuzzy=1` (or
`fallbackFuzzy=true`) and `removeDuplicates=1` (or `removeDuplicates=true`).
That will mean that if a user mistypes one or two characters, they should still get some sensible results.

`removeDuplicates` is designed to create a better user experience when searching SNOMED CT. In general, during search,
you will want to show to the user the multiple synonyms for a given concept. Recently however, and particularly if you
are using multiple SNOMED CT distributions (e.g. both the UK clinical and drug extensions), then a single concept may
have multiple synonyms with the same textual content. This can be disconcerting for end-users as it looks as if there
are duplicates in the autocompletion list. Each, of course, has a different description id, but we do not show identifiers
to end-users. To improve the user experience, I advise using `removeDuplicates` to remove consecutive results with the 
same conceptId and text.

Here I search for all UK medicinal products with the name amlodipine and populate my autocompletion control using the
results:

```shell
http '127.0.0.1:8080/v1/snomed/search?s=amlodipine\&constraint=<10363601000001109&fallbackFuzzy=true&removeDuplicates=true&maxHits=500'
```

Try it live: [http://128.140.5.148:8080/v1/snomed/search?s=amlodipine\&constraint=<10363601000001109&fallbackFuzzy=true&removeDuplicates=true&maxHits=500](http://128.140.5.148:8080/v1/snomed/search?s=amlodipine\&constraint=<10363601000001109&fallbackFuzzy=true&removeDuplicates=true&maxHits=500)

More complex expressions are supported, and no search term is actually needed.

Let's get all drugs with exactly three active ingredients:

```shell
http '127.0.0.1:8080/v1/snomed/search?constraint=<373873005|Pharmaceutical / biologic product| : [3..3]  127489000 |Has active ingredient|  = <  105590001 |Substance|'
```

Try it live: [http://128.140.5.148:8080/v1/snomed/search?constraint=<373873005|Pharmaceutical / biologic product| : [3..3]  127489000 |Has active ingredient|  = <  105590001 |Substance|](http://128.140.5.148:8080/v1/snomed/search?constraint=%3C373873005%7CPharmaceutical%20/%20biologic%20product%7C%20:%20%5B3..3%5D%20%20127489000%20%7CHas%20active%20ingredient%7C%20%20=%20%3C%20%20105590001%20%7CSubstance%7C)

Or, what about all disorders of the lung that are associated with oedema?

```shell
http -j '127.0.0.1:8080/v1/snomed/search?constraint= <  19829001 |Disorder of lung|  AND <  301867009 |Edema of trunk|'
```

Try it live: [http://128.140.5.148:8080/v1/snomed/search?constraint=/v1/snomed/search?constraint= <  19829001 |Disorder of lung|  AND <  301867009 |Edema of trunk|](http://128.140.5.148:8080/v1/snomed/search?constraint=%3C19829001%20AND%20%3C301867009)

The ECL can be written more concisely:

```shell
http -j '127.0.0.1:8080/v1/snomed/search?constraint= <19829001 AND <301867009'
```

##### Expanding ECL without search

SNOMED CT provides the [Expression Constraint Language (ECL)](http://snomed.org/ecl) 
to declaratively define constraints for expressions. `hermes` provides support 
for the latest version of ECL. If you are simply expanding an ECL expression 
without search terms, you can use the `expand` endpoint.

```shell
http -j '127.0.0.1:8080/v1/snomed/expand?ecl= <19829001 AND <301867009&includeHistoric=true'
```

Try it live: [http://128.140.5.148:8080/v1/snomed/expand?ecl=<19829001 AND <301867009&includeHistoric=true](http://128.140.5.148:8080/v1/snomed/expand?ecl=%20%3C19829001%20AND%20%3C301867009&includeHistoric=true)

This has an optional parameter `includeHistoric` which can expand the expansion
to include historical associations. This is very useful in analytics. SNOMED 
introduced dedicated historic functionality in ECL v2.0, allowing you to choose
to include historic associations as part of your ECL. You can use either approach
in `hermes`.

For example,

```
<195967001 |Asthma| {{ +HISTORY-MOD }}
```

is an ECL expression that will return Asthma, and all subtypes, including 
those now considered inactive or duplicate. You can read more about the new
[history supplement functionality](https://confluence.ihtsdotools.org/display/DOCECL/6.11+History+Supplements) in ECL2.0 in the [formal documentation](http://snomed.org/ecl).

Try it live: [http://128.140.5.148:8080/v1/snomed/expand?ecl=<<195967001 {{ +HISTORY-MOD }}](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C%3C%20195967001%20%7CAsthma%7C%20%7B%7B%20%2BHISTORY-MOD%20%7D%7D)

As a concept identifier is actually a valid SNOMED ECL expression, you can do this:

```shell
http -j '127.0.0.1:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true'
```
Try it live: [http://128.140.5.148:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true](http://128.140.5.148:8080/v1/snomed/expand?ecl=24700007&includeHistoric=true)


```json

[
    {
        "conceptId": 586591000000100,
        "id": 1301271000000113,
        "preferredTerm": "Multiple sclerosis NOS",
        "term": "Multiple sclerosis NOS"
    },
    {
        "conceptId": 192930001,
        "id": 297181019,
        "preferredTerm": "Multiple sclerosis NOS",
        "term": "Multiple sclerosis NOS"
    },
    {
        "conceptId": 24700007,
        "id": 41398015,
        "preferredTerm": "Multiple sclerosis",
        "term": "Multiple sclerosis"
    }
    ...
]
```

You can search using concrete values. 

Here is SNOMED ECL that will return all products containing 250mg of amoxicillin that 
have an oral dose form:
```
< 763158003 |Medicinal product (product)| :
     411116001 |Has manufactured dose form (attribute)|  = <<  385268001 |Oral dose form (dose form)| ,
    {    <<  127489000 |Has active ingredient (attribute)|  = <<  372687004 |Amoxicillin (substance)| ,
          1142135004 |Has presentation strength numerator value (attribute)|  = #250,
         732945000 |Has presentation strength numerator unit (attribute)|  =  258684004 |milligram (qualifier value)|}
```

You can use `hermes` to expand this:

Try it live:  [http://128.140.5.148:8080/v1/snomed/expand?ecl=<7631580003...](http://128.140.5.148:8080/v1/snomed/expand?ecl=%3C%20763158003%20%7CMedicinal%20product%20%28product%29%7C%20%3A%0A%20%20%20%20%20411116001%20%7CHas%20manufactured%20dose%20form%20%28attribute%29%7C%20%20%3D%20%3C%3C%20%20385268001%20%7COral%20dose%20form%20%28dose%20form%29%7C%20%2C%0A%20%20%20%20%7B%20%20%20%20%3C%3C%20%20127489000%20%7CHas%20active%20ingredient%20%28attribute%29%7C%20%20%3D%20%3C%3C%20%20372687004%20%7CAmoxicillin%20%28substance%29%7C%20%2C%0A%20%20%20%20%20%20%20%20%20%201142135004%20%7CHas%20presentation%20strength%20numerator%20value%20%28attribute%29%7C%20%20%3D%20%23250%2C%0A%20%20%20%20%20%20%20%20%20732945000%20%7CHas%20presentation%20strength%20numerator%20unit%20%28attribute%29%7C%20%20%3D%20%20258684004%20%7Cmilligram%20%28qualifier%20value%29%7C%7D)

Unfortunately, at the time of writing, the UK SNOMED drug extension doesn't 
currently publish concrete values data for products in the UK dictionary of 
medicines and devices, but this is on their roadmap.

##### Crossmap to and from SNOMED CT

There are endpoints for crossmapping to and from SNOMED.

Let's map one of our diagnostic terms into ICD-10:

- `24700007` is multiple sclerosis.
- `999002271000000101` is the ICD-10 UK complex map reference set.

```shell
http -j 127.0.0.1:8080/v1/snomed/concepts/24700007/map/999002271000000101
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/999002271000000101)

Result:

```
[
    {
        "active": true,
        "correlationId": 447561005,
        "effectiveTime": "2020-08-05",
        "id": "57433204-2371-5c6f-855f-94ff9dad7ba6",
        "mapAdvice": "ALWAYS G35.X",
        "mapCategoryId": 1,
        "mapGroup": 1,
        "mapPriority": 1,
        "mapRule": "",
        "mapTarget": "G35X",
        "moduleId": 999000031000000106,
        "referencedComponentId": 24700007,
        "refsetId": 999002271000000101
    }
]
```

And of course, we can crossmap back to SNOMED as well:

```shell
http -j 127.0.0.1:8080/v1/snomed/crossmap/999002271000000101/G35X
```

Try it live: [http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X](http://128.140.5.148:8080/v1/snomed/crossmap/999002271000000101/G35X)

If you map a concept into a reference set that doesn't contain that concept, you'll
automatically get the best parent matches instead.

##### Map a concept into a reference set

You will usually crossmap using a SNOMED CT crossmap reference set, such as those for ICD-10 or OPCS. However, `Hermes` supports
crossmapping a concept into any reference set. You can use this feature in data analytics in order to reduce the dimensionality of your dataset. 

Here we have multiple sclerosis (`24700007`), and we're mapping into the UK emergency unit
reference set (`991411000000109`):

```shell
http -j 127.0.0.1:8080/v1/snomed/concepts/24700007/map/991411000000109
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/991411000000109](http://128.140.5.148:8080/v1/snomed/concepts/24700007/map/991411000000109)

The UK emergency unit reference set gives a subset of concepts used for central reporting problems and diagnoses in UK emergency units. 

As multiple sclerosis in that reference set, you'll simply get:

```json
[
  {
    "active": true,
    "effectiveTime": "2015-10-01",
    "id": "d55ce305-3dcc-5723-8814-cd26486c37f7",
    "moduleId": 999000021000000109,
    "referencedComponentId": 24700007,
    "refsetId": 991411000000109
  }
]
```

But what happens if we try something that isn't in that emergency reference set?

Here is 'limbic encephalitis with LGI1 antibodies' (`763794005`). It isn't in
that UK emergency unit reference set:

```shell
http -j 127.0.0.1:8080/v1/snomed/concepts/763794005/map/991411000000109
```

Try it live: [http://128.140.5.148:8080/v1/snomed/concepts/763794005/map/991411000000109](http://128.140.5.148:8080/v1/snomed/concepts/763794005/map/991411000000109)


Result:

```json
[
  {
    "active": true,
    "effectiveTime": "2015-10-01",
    "id": "5b3b8cdd-dd02-50e3-b207-bf4a3aa17694",
    "moduleId": 999000021000000109,
    "referencedComponentId": 45170000,
    "refsetId": 991411000000109
  }
]
```

You get a more general concept - 'encephalitis' (`45170000`) that is in the
emergency unit reference set. This makes it straightforward to map concepts
into subsets of terms as defined by a reference set for analytics.

You could limit users to only entering the terms in a subset, but much better to allow clinicians to regard highly-specific granular terms and be able to map to less granular terms on demand.

### C. Embed into another application

You can use git coordinates in a deps.edn file, or use maven:

In your `deps.edn` file (make sure you change the commit-id):

```
[com.eldrix.hermes {:git/url "https://github.com/wardle/hermes.git"
                    :sha     "097e3094070587dc9362ca4564401a924bea952c"}
``` 

In your pom.xml:

```xml
<dependency>
  <groupId>com.eldrix</groupId>
  <artifactId>hermes</artifactId>
  <version>1.0.960</version>
</dependency>
```
Remember to use the latest version.

You may need to add Clojars as a repository in your build tool. Here for maven:

```xml
<repositories>
    <repository>
        <id>clojars.org</id>
        <url>https://clojars.org/repo</url>
    </repository>
</repositories>
```

### D. Development

See [/doc/development](/doc/development.md) on how to develop, test, lint, deploy and release `hermes`. 

### E. Backwards compatibility and versioning

`Hermes` uses versions of form `major.minor.commit`

`Hermes` builds a file-based database made up of a store and indices
and each database is also versioned. `Hermes` of a specified major/minor
version is compatible with databases created by the same major/minor version. 
For example, a database was created with `Hermes v1.4.1265` can be read
by `Hermes v1.4.1320`, but one created with `Hermes v1.3.1262` cannot

If backwards compatibility can easily be preserved, the major/minor version is 
kept the same. For example, when support for concrete values was added, this was 
an additive change so that newer versions of `Hermes` would simply degrade
gracefully, but throw a warning to say concrete values were not supported for 
this database.

On some occasions, compatibility is broken even when there is only a minor 
change to database format to prevent user inconvenience, error or confusion. For
example, in the change from 1.3 series to 1.4, the search index changed to use
normalised (folded) text according to term locale. This was a small change
and degradation could have occurred gracefully, but such a fallback would lead
to varying behaviour depending on which database was used and potentially 
confuse users. 

In general therefore, the policy for versioning is to enforce exact version 
matching for a given `Hermes` and database version with a bias towards bumping 
versions when backwards compatibility or fallback modes of operation could 
result in confusing or unexpected behaviour.

*Mark*
