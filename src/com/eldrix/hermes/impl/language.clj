(ns com.eldrix.hermes.impl.language
  "SNOMED language and localisation support.
  There are four ways to consider locale in SNOMED CT.
  1. A crude language label (e.g. \"en\" in each description.
     This should generally not be used.
  2. Requesting using one or more specific language reference sets.
     This provides the most control for clients but the burden of work
     falls on clients to decide which reference sets to use at each request.
  3. Request by a dialect alias.
     This simply maps aliases to specific language reference sets.
  4. Request by IETF BCP 47 locale.

  Options 1-3 are defined by SNOMED.
  Option 4 provides a wrapper so that standards-based locale priority strings
  can be used effectively.

  Arguably, there are two separate issues. For clients, they want to be able
  to choose their locale and this should use IETF BCP 47. For any single
  service installation, there will need to be a choice in how a specific
  locale choice is met. "
  (:require [com.eldrix.hermes.impl.store :as store]
            [clojure.string :as str])
  (:import (java.util Locale$LanguageRange Locale)))

(def language-reference-sets
  "Defines a mapping between ISO language tags and the language reference sets
  (possibly) installed as part of SNOMED CT. These can be used if a client
  does not wish to specify a reference set (or set of reference sets) to be used
  to derive preferred or acceptable terms, but instead wants some sane defaults
  on the basis of locale as defined by IETF BCP 47.

  These are based on the contents of https://confluence.ihtsdotools.org/display/DOCECL/Appendix+C+-+Dialect+Aliases
  but hide the unnecessary complexity of multiple language reference sets
  for GB, and instead provide an ordered priority list.

  TODO: consider a registry so that client applications can register a
  different set of priorities for any specific locale if they know better
  than the defaults."
  {"en-gb" [999001261000000100                              ;; NHS realm language (clinical part)
            999000691000001104                              ;; NHS realm language (pharmacy part)
            900000000000508004                              ;; Great Britain English language reference set
            999001251000000103]                             ;; United Kingdom Extension Great Britain English language reference set
   "en-us" [900000000000509007]
   "en-ca" [19491000087109]                                 ;; |Canada English language reference set|
   "en-ie" [21000220103]                                    ;; |Irish language reference set|
   "en-nz" [271000210107]                                   ;; |New Zealand English language reference set|
   "es"    [448879004]                                      ;; |Spanish language reference set|
   "es-ar" [450828004]                                      ;;|Conjunto de referencias de lenguaje castellano para América Latina|
   "es-uy" [5641000179103]                                  ;; |Conjunto de referencias de lenguaje castellano para Uruguay|
   "et-ee" [71000181105]                                    ;; |Estonian language reference set|
   "de"    [722130004]                                      ;; |German language reference set|
   "fr"    [722131000]                                      ;; |French language reference set|
   "fr-be" [21000172104]                                    ;; |Belgian French language reference set|
   "fr-ca" [20581000087109]                                 ;; |Canada French language reference set|
   "ja"    [722129009]                                      ;; | Japanese language reference set|
   "nl-be" [31000172101]                                    ;;|Belgian Dutch language reference set|
   "nl-nl" [31000146106]                                    ;; |Netherlands Dutch language reference set|
   "nb-no" [61000202103]                                    ;; |Norwegian Bokmål language reference set|
   "nn-no" [91000202106]                                    ;; |Norwegian Nynorsk language reference set|
   "sv-se" [46011000052107]                                 ;; |Swedish language reference set|
   "zh"    [722128001]                                      ;; |Chinese language reference set|
   })


(def dialect-language-reference-sets
  "These use non-standard locales that cannot be used with the conventional
  locale matching standards, such as  IETF BCP 47, but they are used in ECL for
  a crude alias for dialect, with an alias representing a distinct reference
  set.
  See https://confluence.ihtsdotools.org/display/DOCECL/Appendix+C+-+Dialect+Aliases"
  {"en-int-gmdn"     608771002                              ;; |GMDN language reference set|
   "en-nhs-clinical" 999001261000000100                     ;; |National Health Service realm language reference set (clinical part)|
   "en-nhs-dmd"      999000671000001103                     ;; |National Health Service dictionary of medicines and devices realm language reference set|
   "en-nhs-pharmacy" 999000691000001104                     ;; |National Health Service realm language reference set (pharmacy part)|
   "en-uk-drug"      999000681000001101                     ;; |United Kingdom Drug Extension Great Britain English language reference set|
   "en-uk-ext"       999001251000000103                     ;; |United Kingdom Extension Great Britain English language reference set|
   "en-gb"           900000000000508004                     ;; |Great Britain English language reference set|
   })

(def ^:private dialect-aliases
  "A simple map of dialect alias to refset identifier."
  (merge (reduce-kv (fn [m k v] (assoc m k (first v))) {} language-reference-sets) dialect-language-reference-sets))

(defn dialect->refset-id
  "Return the refset identifier for the dialect specified."
  [s]
  (when s (get dialect-aliases (str/lower-case s))))

;;;;
;;;;
;;;;
;;;;
;;;;
;;;;

(defn- filter-language-reference-sets
  "Filters the mapping from ISO language tags to refsets keeping only those
  with installed reference sets."
  [mapping installed]
  (reduce-kv (fn [m k v]
               (if-let [filtered (seq (filter installed v))]
                 (assoc m (Locale/forLanguageTag k) filtered)
                 (dissoc m k)))
             {}
             mapping))

(defn- do-match
  [installed-language-reference-sets language-priority-list]
  (let [installed-locales (keys installed-language-reference-sets) ;; list of java.util.Locales
        priority-list (try (Locale$LanguageRange/parse language-priority-list) (catch Exception _ []))
        filtered (Locale/filter priority-list installed-locales)]
    (mapcat #(get installed-language-reference-sets %) filtered)))

(defn match-fn
  " Generate a locale matching function to return the best refsets to use given a
  'language-priority-list'.
  Parameters:
  - store : a SNOMED store
  Returns:
  - a function that takes a single string containing a list of comma-separated
  language ranges or a list of language ranges in the form of the
  \"Accept-Language \" header defined in RFC 2616.

  This closes over the installed reference sets at the time of function
  generation and so does not take into account of changes made since
  it was generated. "
  [store]
  (let [installed-refsets (store/get-installed-reference-sets store)
        installed-language-reference-sets (filter-language-reference-sets language-reference-sets installed-refsets)]
    (partial do-match installed-language-reference-sets)))

(defn match
  "Convenience method to match a language-priority-list to the installed
  language reference sets in the store specified. "
  [store language-priority-list]
  ((match-fn store) language-priority-list))