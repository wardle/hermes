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

It is fast, both for import and for use. It imports and indexes the International
and UK editions of SNOMED CT in a total of 7 minutes; you can have a server
running seconds after that. 

It replaces previous similar tools written in java and golang and is designed to fit into a wider architecture
with identifier resolution, mapping and semantics as first-class abstractions.

It is part of my PatientCare v4 development; previous versions have been operational within NHS Wales
since 2007. 

### A. How to download and build a terminology service

Ensure you have a pre-built jar file, or the source code checked out from github. See below for build instructions.

I'd recommend installing clojure and running using source code but use the pre-built jar file if you prefer.

#### 1. Download and install at least one distribution.

If your local distributor is supported, `hermes` can do this automatically for you. 
Otherwise, you will need to download your local distribution(s) manually.

##### i) Use a registered SNOMED CT distributor to automatically download and import

There is currently only support for automatic download and import for the UK,
but other distribution sources can be added if those services provide an API.

The basic command is:

```shell
clj -M:run --db snomed.db download <distribution-identifier> [properties] 
```

or if you are using a precompiled jar:
```shell
java -jar hermes.jar --db snomed.db download <distribution-identifier> [properties]
```

The distribution, as defined by `distribution-identifier`, will be downloaded
and imported to the file-based database `snomed.db`. 

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

For example, these commands will download, cache and install the International
release, the UK clinical edition and the UK drug extension:
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
a total of less than 3 minutes on my machine.

#### 2. Compact database (optional).

This reduces the file size by around 20% and takes about 1 minute.
This is an optional step, but recommended.

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

```shell
clj -M:run --db snomed.db index
```
This will build the search index; it takes about 2 minutes on my machine.

#### 4. Run a REPL (optional)

When I first built terminology tools, either in java or in golang, I needed to
also build a custom command-line interface in order to explore the ontology.
This is not necessary as most developers using Clojure quickly learn the value
of the REPL; a read-evaluate-print-loop in which one can issue arbitrary 
commands to execute. As such, one has a full Turing-complete language (a lisp)
in which to explore the domain. 

Run a REPL and use the terminology services interactively. I usually use a 
REPL from within my IDE.

```
clj -A:dev
```

#### 5. Run a terminology web service

By default, data are returned using [edn](https://github.com/edn-format/edn) but
of course, simply add "Accept:application/json" in the request header and it
will return JSON instead. You can see examples below. 

```
java -jar hermes.jar --db snomed.db --port 8080 serve 
```

or
```
clj -M:run --db snomed.db --port 8080 serve
```

Example usage of search endpoint. 

```shell
curl "http://localhost:8080/v1/snomed/search?s=mnd\&constraint=<64572001&maxHits=5" -H "Accept: application/json"  | jq
````

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

Here I use the [httpie](https://httpie.io/) command-line tool:

```shell
http -j localhost:8080/v1/snomed/concepts/24700007/extended
```

The result is an extended concept definition - all the information
needed for inference, logic and display. For example, at the client
level, we can then check whether this is a type of demyelinating disease
or is a disease affecting the central nervous system without further
server round-trips. Each relationship also includes the transitive closure tables for that
relationship, making it easier to execute logical inference.
Note how the list of descriptions includes a convenient
`acceptable-in` and `preferred-in` so you can easily display the preferred
term for your locale.


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
            "acceptable-in": [],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 41398015,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferred-in": [
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
            "acceptable-in": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2002-01-31",
            "id": 41399011,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferred-in": [],
            "refsets": [],
            "term": "Multiple sclerosis, NOS",
            "typeId": 900000000000013009
        },
        {
            "acceptable-in": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2015-01-31",
            "id": 41400016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferred-in": [],
            "refsets": [],
            "term": "Generalized multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptable-in": [],
            "active": false,
            "caseSignificanceId": 900000000000020002,
            "conceptId": 24700007,
            "effectiveTime": "2015-01-31",
            "id": 481990016,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferred-in": [],
            "refsets": [],
            "term": "Generalised multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptable-in": [],
            "active": true,
            "caseSignificanceId": 900000000000448009,
            "conceptId": 24700007,
            "effectiveTime": "2017-07-31",
            "id": 754365011,
            "languageCode": "en",
            "moduleId": 900000000000207008,
            "preferred-in": [
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
            "acceptable-in": [
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
            "preferred-in": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "Disseminated sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptable-in": [
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
            "preferred-in": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "MS - Multiple sclerosis",
            "typeId": 900000000000013009
        },
        {
            "acceptable-in": [
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
            "preferred-in": [],
            "refsets": [
                900000000000509007,
                900000000000508004,
                999001261000000100
            ],
            "term": "DS - Disseminated sclerosis",
            "typeId": 900000000000013009
        }
    ],
    "direct-parent-relationships": {
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
    "parent-relationships": {
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

Here we use the expression constraint language to search for a term "mnd" 
ensuring we only receive results that are a type of 'Disease' ("<64572001")

```shell
http -j 'localhost:8080/v1/snomed/search?s=mnd\&constraint=<64572001'
```

Results:

```
http -j 'localhost:8080/v1/snomed/search?s=mnd\&constraint=<64572001'
[
    {
        "conceptId": 37340000,
        "id": 486696014,
        "preferredTerm": "Motor neuron disease",
        "term": "MND - Motor neurone disease"
    }
]

```

More complex expressions are supported, and no search term is actually needed.

Let's get all drugs with exactly three active ingredients:

```shell
http -j 'localhost:8080/v1/snomed/search?constraint=<373873005|Pharmaceutical / biologic product| : [3..3]  127489000 |Has active ingredient|  = <  105590001 |Substance|'
```

Or, what about all disorders of the lung that are associated with oedema?
```shell
http -j 'localhost:8080/v1/snomed/search?constraint= <  19829001 |Disorder of lung|  AND <  301867009 |Edema of trunk|'
```

The ECL can be written in a more concise fashion:

```shell
http -j 'localhost:8080/v1/snomed/search?constraint= <19829001 AND <301867009'
```


There are endpoints for crossmapping to and from SNOMED.

Let's map one of our diagnostic terms into ICD-10:

```shell
http -j localhost:8080/v1/snomed/concepts/24700007/map/999002271000000101
```

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
http -j localhost:8080/v1/snomed/crossmap/999002271000000101/G35X
```


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

