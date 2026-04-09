# Interactive exploration with the REPL

When I previously built terminology tools in Java and Go, I had to build a 
custom command-line browser to explore the ontology. With Clojure, this is 
unnecessary — the REPL gives you an interactive portal into SNOMED CT with 
the full power of a general-purpose programming language.

## Starting the REPL

```shell
clj -M:dev
```

This starts an interactive REPL. 

If you use an editor with Clojure support (Emacs, Neovim, VS Code with Calva, IntelliJ with Cursive), use your editor to start a REPL,
or start an nREPL server instead with `clj -M:dev:nrepl-server` and connect from your editor.

## Testing your REPL

```clojure
(+ 1 1)
=> 2
```

Clojure is a lisp and uses prefix notation.

If you're more familiar with infix notation such as `1 + 1` then you simply move the 'operation' to the start

ie `1 + 1` becomes `(+ 1 1)`

And function calls are the same...

ie `dostuff(1)` becomes `(dostuff 1)`

There aren't more parentheses... they're just in different places! ;)

And you can think of () as a cell in a spreadsheet, so: `(* (+ 1 1) 2)` => `4`

## Opening a service

```clojure
(require '[com.eldrix.hermes.core :as hermes])

(def svc (hermes/open "snomed.db"))
```

This 'defines' the 'svc' to be an opaque handle representing the Hermes 'service'.

All functions take `svc` as the first argument.

## Exploring concepts

```clojure
;; Look up a concept
(hermes/concept svc 24700007)
;; => {:id 24700007, :active true, :definitionStatusId 900000000000074008, ...}

;; Get the preferred description
(hermes/preferred-synonym svc 24700007 "en-GB")
;; => #Description{:term "Multiple sclerosis", ...}

(:term (hermes/preferred-synonym svc 24700007 "en-GB"))
;; => "Multiple sclerosis"

;; Get the fully specified name (includes semantic tag)
(:term (hermes/fully-specified-name svc 24700007))
;; => "Multiple sclerosis (disorder)"

;; All synonyms
(hermes/synonyms svc 24700007)

;; Everything at once — descriptions, relationships, refset memberships
(hermes/extended-concept svc 24700007)
```

## Navigating the hierarchy

```clojure
;; Is multiple sclerosis a type of demyelinating disease?
(hermes/subsumed-by? svc 24700007 6118003)
;; => true

;; All ancestors (transitive IS-A closure)
(hermes/all-parents svc 24700007)
;; => #{6118003 404684003 64572001 ...}

;; All descendants
(hermes/all-children svc 24700007)

;; All paths to the root concept
(hermes/paths-to-root svc 24700007)

;; Defining properties, grouped by role group
(hermes/properties svc 24700007)

;; Pretty-printed with human-readable terms
(hermes/pprint-properties svc (hermes/properties svc 24700007))
```

## Searching

```clojure
;; Free-text search
(hermes/search svc {:s "heart attack" :max-hits 5})

;; Constrained to disorders
(hermes/search svc {:s "diabetes" :constraint "<64572001" :max-hits 10})

;; Constrained to UK medicinal products
(hermes/search svc {:s "amlodipine" :constraint "<10363601000001109" :max-hits 20})

;; Abbreviations work — hermes indexes all synonyms
(hermes/search svc {:s "mnd" :constraint "<64572001" :max-hits 5})
;; => [{:term "MND - Motor neurone disease", :conceptId 37340000, ...}]
```

## Expression Constraint Language (ECL)

```clojure
;; Expand an ECL expression — returns Result records
(hermes/expand-ecl svc "<<73211009")
;; => (#Result{:conceptId 73211009, :term "Diabetes mellitus", ...} ...)

;; Results have :conceptId, :term and :preferredTerm
(map :preferredTerm (hermes/expand-ecl svc "<!24700007"))
;; => ("Progressive multiple sclerosis" ...)

;; Include historical associations
(hermes/expand-ecl-historic svc "<<24700007")

;; Filter a set of concept IDs by ECL
(hermes/intersect-ecl svc #{24700007 73211009 22298006} "<64572001")
;; => #{24700007 73211009 22298006}

;; Check ECL syntax
(hermes/valid-ecl? "<64572001")
;; => true
```

## Cross-mapping

```clojure
;; Get ICD-10 mapping for multiple sclerosis
(map :mapTarget (hermes/component-refset-items svc 24700007 999002271000000101))
;; => ("G35X")

;; Map from ICD-10 back to SNOMED
(hermes/reverse-map svc 999002271000000101 "G35X")

;; Which reference sets does this concept belong to?
(hermes/component-refset-ids svc 24700007)

;; Classify concepts into a target reference set (e.g. for analytics)
(hermes/map-into svc [24700007 73211009] 991411000000109)
;; => (#{24700007} #{362969004})
```

In the first example, the Clojure `map` function applies a function to a sequence of elements. In this case, looking
up the `mapTarget` of each reference set item returned.

## Compositional grammar

```clojure
;; Parse an expression
(hermes/parse-expression svc "24700007 |Multiple sclerosis|")
;; => {:definitionStatus :equivalent-to, :subExpression {:focusConcepts [{:conceptId 24700007, :term "Multiple sclerosis"}]}}

;; Render with preferred terms
(hermes/render-expression svc "24700007" "en-GB")
;; => "=== 24700007|Multiple sclerosis|"

;; Validate an expression (nil means valid)
(hermes/validate-expression svc "24700007:{363698007=39057004}")
;; => nil
```

## Putting it together

The real power is combining these operations using Clojure. `expand-ecl` returns
Result records — map over them to extract what you need:

```clojure
;; What are the direct subtypes of multiple sclerosis?
(->> (hermes/expand-ecl svc "<!24700007")
     (map :preferredTerm)
     distinct
     sort)
```

Build richer results by combining functions:

```clojure
;; All types of diabetes with their ICD-10 codes
(->> (hermes/expand-ecl svc "<<73211009")
     (map (fn [{:keys [conceptId preferredTerm]}]
            {:term   preferredTerm
             :icd-10 (map :mapTarget (hermes/component-refset-items svc conceptId 999002271000000101))}))
     (filter #(seq (:icd-10 %)))
     (distinct)
     (sort-by :term)
     (take 10))
```
=>

```clojure
({:term "Atherosclerosis, deafness, diabetes, epilepsy, nephropathy syndrome", :icd-10 ("Q878")} 
 {:term "Atypical diabetes mellitus", :icd-10 ("O243" "E139")} 
 {:term "Brittle diabetes mellitus", :icd-10 ("E109" "E108" "O240")} 
 {:term "Brittle type 1 diabetes mellitus", :icd-10 ("O240" "E109")} 
 {:term "Brittle type 2 diabetes mellitus", :icd-10 ("O241" "E119")} 
 {:term "DEND syndrome", :icd-10 ("G409" "R629" "P702")} 
 {:term "Diabetes mellitus", :icd-10 ("E148" "E139" "E138" "O249" "P702" "E149")} 
 {:term "Diabetes mellitus associated with genetic syndrome", :icd-10 ("E139" "O243")} 
 {:term "Diabetes mellitus associated with hormonal aetiology", :icd-10 ("E139" "O243")} 
 {:term "Diabetes mellitus associated with pancreatic disease", :icd-10 ("E139" "O243" "K869")})
```

This is fundamentally different from using a REST API or a fixed browser — you 
have the full expressiveness of a programming language to ask arbitrary questions 
of the terminology.

## Visual inspection with Morse

The dev alias includes [Morse](https://github.com/nubank/morse), a graphical 
data inspector. Use `tap>` to send data to it:

```clojure
(require '[dev.nu.morse :as morse])
(morse/launch-in-proc)

(tap> (hermes/extended-concept svc 24700007))
(tap> (hermes/search svc {:s "polymyositis" :constraint "<404684003"}))
```

## Benchmarking

The dev alias also includes [criterium](https://github.com/hugoduncan/criterium) 
for statistically rigorous benchmarking:

```clojure
(require '[criterium.core :as crit])

(crit/bench (hermes/concept svc 24700007))
(crit/bench (hermes/search svc {:s "multiple sclerosis" :max-hits 10}))
(crit/bench (hermes/subsumed-by? svc 24700007 6118003))
```
