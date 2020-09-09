# hermes

`hermes` is a clojure-based suite of terminology tools including a fast server with full-text search functionality.

It replaces previous similar tools written in java and golang and is designed to fit into a wider architecture
with identifier resolution, mapping and semantics as first-class abstractions.

#### Run from the command-line

```
clj -m com.eldrix.hermes.core
```

#### Running tests
```
clj -A:test
```   

#### Building uberjar

Perform ahead-of-time (AOT) compilation (see [https://clojure.org/guides/deps_and_cli#aot_compilation](https://clojure.org/guides/deps_and_cli#aot_compilation))
```
clj -e "(compile 'app.core)"
```

This will generate class files to create a runnable jar. Now you can build an uberjar:

```
clojure -A:uberjar
```
