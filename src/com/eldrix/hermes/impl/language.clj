; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.language
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
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util Locale$LanguageRange Locale)))

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

  {"da-dk" [554461000005103]                                ;; Danish
   "en-au" [32570271000036106]                              ;; Australian
   "en-gb" [999001261000000100                              ;; NHS realm language (clinical part)
            999000691000001104                              ;; NHS realm language (pharmacy part)
            999000671000001103                              ;; UK dm+d
            999000681000001101                              ;; UK drug extension
            900000000000508004                              ;; Great Britain English language reference set
            999001251000000103]                             ;; United Kingdom Extension Great Britain English language reference set
   "en-us" [900000000000509007]
   "en-ca" [19491000087109]                                 ;; |Canada English language reference set|
   "en-ie" [21000220103]                                    ;; |Irish language reference set|
   "en-nz" [271000210107]                                   ;; |New Zealand English language reference set|
   "es-es" [448879004]                                      ;; |Spanish language reference set|
   "es-ar" [450828004]                                      ;; |Conjunto de referencias de lenguaje castellano para América Latina|
   "es-uy" [5641000179103]                                  ;; |Conjunto de referencias de lenguaje castellano para Uruguay|
   "et-ee" [71000181105]                                    ;; |Estonian language reference set|
   "de-de" [722130004]                                      ;; |German language reference set|
   "fr-fr" [722131000]                                      ;; |French language reference set|
   "fr-be" [21000172104]                                    ;; |Belgian French language reference set|
   "fr-ca" [20581000087109]                                 ;; |Canada French language reference set|
   "ja-ja" [722129009]                                      ;; | Japanese language reference set|
   "mi"    [291000210106]
   "nl-be" [31000172101]                                    ;; |Belgian Dutch language reference set|
   "nl-nl" [31000146106]                                    ;; |Netherlands Dutch language reference set|
   "nb-no" [61000202103]                                    ;; |Norwegian Bokmål language reference set|
   "nn-no" [91000202106]                                    ;; |Norwegian Nynorsk language reference set|
   "sv-se" [46011000052107]                                 ;; |Swedish language reference set|
   "zh"    [722128001]})                                    ;; |Chinese language reference set|


(def language-refset-id->locale
  "Given a language reference set id, return the matching locale, if known."
  (reduce-kv (fn [acc k refset-ids]
               (let [locale (Locale/forLanguageTag k)]
                 (loop [acc acc, refset-ids refset-ids]
                   (if-let [refset-id (first refset-ids)]
                     (recur (assoc acc refset-id locale) (next refset-ids))
                     acc))))
             {} language-reference-sets))

(def language-refset-id->language
  "Given a language reference id, return the language tag.
  For example
  ```
  (language-refset-id->language 722131000)
  => \"fr\"
  ```."
  (reduce-kv (fn [acc k refset-ids]
               (let [lang (.getLanguage (Locale/forLanguageTag k))]
                 (loop [acc acc, refset-ids refset-ids]
                   (if-let [refset-id (first refset-ids)]
                     (recur (assoc acc refset-id lang) (next refset-ids))
                     acc))))
             {} language-reference-sets))

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
   "en-gb"           900000000000508004})                   ;; |Great Britain English language reference set|


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

(defn parse-accept-language-refset-id
  "Parse an 'Accept-Language' header specifying a refset identifier.
  This header should be of the form 'en-x-12345678902'
  The prefix should be any [BCP 47 language tag](https://tools.ietf.org/html/bcp47),
  but this information will be ignored in favour of the specified refset
  identifier.
  Returns the refset specified or nil if the header isn't suitable."
  [s]
  (let [[_ _ concept-id] (re-matches #"^.+-(x|X)-(\d++)$" (or s ""))]
    (when (and (= :info.snomed/Concept (snomed/identifier->type concept-id))
               (verhoeff/valid? concept-id))
      (Long/parseLong concept-id))))

(defn locale->installed-reference-set-ids
  "Return a map of recognized, installed language reference sets keyed by
  `Locale`.
  For example
  {#object[java.util.Locale 0x31e2875 \"en_GB\"] (999001261000000100 900000000000508004),
   #object[java.util.Locale 0x7d55b894 \"en_US\"] (900000000000509007)}"
  [store]
  (let [installed-refset-ids (store/installed-reference-sets store)]
    (reduce-kv (fn [m k v]
                 (if-let [filtered (seq (filter installed-refset-ids v))]
                   (assoc m (Locale/forLanguageTag k) filtered)
                   m)) {} language-reference-sets)))

(defn ^:private locale-priority
  "Returns a 'priority' score for a given locale. At the moment this simply
  priorities everything above en-US, which is what is provided in the
  International edition."
  [^Locale locale]
  (if (= Locale/US locale) 1 0))

(defn available-locales
  "Returns a sequence of recognised, installed locales. Results are returned in
  a meaningful order, in which locales that are defined in country-specific
  extensions are listed before those from the International edition. "
  [store]
  (let [installed-refset-ids (store/installed-reference-sets store)
        installed-locales (reduce-kv
                            (fn [acc k [refset-id & _]]     ;; we check that the FIRST reference set id is installed, rather than ANY in the list
                              (if (installed-refset-ids refset-id)
                                (conj acc (.toLanguageTag (Locale/forLanguageTag k)))
                                acc)) [] language-reference-sets)]
    (sort-by locale-priority installed-locales)))

(defn default-fallback-locale
  "Returns a string representing the 'best' fallback locale given what is
  installed in the store, in the format of an Accept-Language header."
  [store]
  (str/join "," (available-locales store)))

(defn- do-match
  "Performs a locale match given the installed locales, a language priority
  list and a fallback priority list. Returns a list of reference set ids.
  Parameters:
  - available : a map of installed reference sets. See `locale->installed-reference-set-ids`
  - fallback-priority-list : priority list in case none in chosen list available
  - language-priority-list : an 'Accept-Language' string (e.g. \"en-GB,en\")
  - fallback? : if a match cannot be found, should fallback to fallback list?

  By default, there is no fallback."
  ([available language-priority-list]
   (if-let [specific-refset-id (parse-accept-language-refset-id language-priority-list)]
     (let [installed-refsets (-> available vals flatten set)]
       (filter installed-refsets [specific-refset-id]))
     (let [locales (keys available)                         ;; installed locales
           priority-list (try (Locale$LanguageRange/parse language-priority-list) (catch Exception _ []))
           filtered (Locale/filter priority-list (or locales '()))]
       (mapcat #(get available %) filtered))))
  ([available fallback-priority-list language-priority-list]
   (do-match available fallback-priority-list language-priority-list false))
  ([available fallback-priority-list language-priority-list fallback?]
   (if-let [result (seq (do-match available language-priority-list))]
     result
     (when (and fallback? fallback-priority-list)
       (do-match available fallback-priority-list)))))

(defn make-match-fn
  "Generate a locale matching function to return the best reference sets to use
  given a 'language-priority-list' such as \"fr,en-US\".
  Parameters:
  - store : a SNOMED store
  - fallback-priority-list : default locale to use, e.g. \"en-GB\". Can be nil.

  If `fallback-priority-list` is nil, a sensible fallback locale will be chosen
  from what is installed in the store.

  Returns a multi-arity function that returns a sequence of best-matched
  reference set identifiers to be used in order of priority.
  - [] : returns a set representing the database default locale.
  - [priority-list] : returns a set best matching a priority list - which should
  be a single string containing a list of comma-separated language ranges
  or a list of language ranges in the form of the \"Accept-Language \" header
  defined in RFC 2616. By default, does not fallback to database default.
  - [priority-list fallback?] - as above, but if the preferences in the priority
  list cannot be met, should the database default fallback be used?

  This closes over the installed reference sets at the time of function
  generation and so does not take into account changes made since
  it was generated.

  Throws an exception if the specified fallback language range cannot be
  supported, or if there is no recognised available locale installed at all."
  ([st fallback-priority-list]
   (let [available (locale->installed-reference-set-ids st)
         fallback (or fallback-priority-list (default-fallback-locale st))
         fallback-refset-ids (do-match available nil fallback)]
     (when (empty? fallback-refset-ids)
       (let [installed (store/installed-language-reference-sets st)
             err {:requested    fallback-priority-list      ;; what was requested
                  :available    (available-locales st)      ;; what is available (ie installed and recognized)
                  :unrecognized (remove language-refset-id->locale installed) ;; what is installed but not recognized
                  :installed    installed}]                 ;; what is installed
         (log/error "No language reference set installed matching requested fallback (default) locale." err)
         (log/error "Explicitly choose a different default locale or install additional distributions")
         (throw (ex-info "No language reference set installed matching requested fallback (default) locale." err))))
     (fn
       ([]
        fallback-refset-ids)
       ([language-priority-list]
        (do-match available fallback language-priority-list false))
       ([language-priority-list fallback?]
        (if (and (str/blank? language-priority-list) fallback?)
          fallback-refset-ids
          (do-match available fallback language-priority-list fallback?)))))))
;;(memoize (partial do-match (installed-language-reference-sets store) fallback-priority-list))))

(defn match
  "Convenience method to match a language-priority-list to the installed
  language reference sets in the store specified.
  Returns a sequence of reference set identifiers."
  [store language-priority-list]
  ((make-match-fn store language-priority-list)))

(comment
  (parse-accept-language-refset-id "en-x-999001261000000100")
  (parse-accept-language-refset-id "en-gb-x-999001261000000100")
  (parse-accept-language-refset-id "zh-yue-HK-x-999001261000000100")
  (parse-accept-language-refset-id "fr-x-999001261000000102"))


;; Normalisation / text folding
;; Snowstorm uses Lucene's ASCIIFoldingFilter but excludes some chars manually
;; on a per-language basis.
;; See https://github.com/IHTSDO/snowstorm/blob/5303ee10a1320f4febe992f460cb903ad6700494/src/main/java/org/snomed/snowstorm/core/util/DescriptionHelper.java#L75

(defn ^:private normalize-nfkd [s]
  (Normalizer/normalize s Normalizer$Form/NFKD))

(defn ^:private normalize-nfkc [s]
  (Normalizer/normalize s Normalizer$Form/NFKC))

(defn ^:private make-fold
  "Returns a fold fn that removes diacritics with optional excluded characters."
  ([]
   (fn [s]
     (.replaceAll (.matcher #"\p{M}" (normalize-nfkd s)) "")))
  ([excluded-chars]
   (fn [s]
     (let [exclude (set (str excluded-chars (str/upper-case excluded-chars)))
           s (->> (seq (normalize-nfkc s))
                  (map #(if (contains? exclude %) % (normalize-nfkd (str %))))
                  (apply str))]
       (.replaceAll (.matcher #"\p{M}" s) "")))))

(def ^:private folding-rules
  [{:lang "da" :exclude "æøå"}                              ;; Danish
   {:lang "fi" :exclude "åäö"}                              ;; Finnish}
   {:lang "fr" :exclude nil}                                ;; French
   {:lang "no" :exclude "æøå"}                              ;; Norwegian
   {:lang "es" :exclude nil}                                ;; Spanish
   {:lang "en" :exclude nil}                                ;; English
   {:lang "sv" :exclude "åäö"}])                            ;; Swedish

(def ^:private fold-by-lang
  "A map of language code to fold function."
  (reduce (fn [acc {:keys [lang exclude]}]
            (assoc acc lang (if exclude (make-fold exclude) (make-fold))))
          {} folding-rules))

(def ^:private fold-by-refset-id
  "A map of language refset id to fold function"
  (reduce-kv (fn [acc k v]
               (assoc acc k (fold-by-lang v))) {} language-refset-id->language))

(def ^:private fold-by-lang-or-refset-id
  "Precomputed lookup for fold function by language (e.g. \"en\" or language
  refset identifier."
  (merge fold-by-lang fold-by-refset-id))

(defn fold
  "Fold (normalize) text according to the rules of a language.
  - lang-or-refset-id : a language code (e.g. \"en\") or language refset id.
  - s                 : string to normalize"
  ^String [lang-or-refset-id s]
  (let [f (or (get fold-by-lang-or-refset-id lang-or-refset-id) (make-fold))]
    (f s)))

(comment
  (fold "es" "nódulo hepático")
  (fold 450828004 "nódulo hepático"))

