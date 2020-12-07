(ns com.eldrix.hermes.impl.language
  (:require [com.eldrix.hermes.impl.store :as store])
  (:import (java.util Locale$LanguageRange Locale)))

(def language-reference-sets
  "Defines a mapping between ISO language tags and the language reference sets
  (possibly) installed as part of SNOMED CT. These can be used if a client
  does not wish to specify a reference set (or set of reference sets) to be used
  to derive preferred or acceptable terms, but instead wants some sane defaults
  on the basis of locale as defined by IETF BCP 47.

  TODO: add more well-known mappings and ordered reference sets representing
  those locales."
  {"en-GB" [999001261000000100                              ;; NHS realm language (clinical part)
            999000691000001104                              ;; NHS realm language (pharmacy part)
            900000000000508004                              ;; Great Britain English language reference set
            ]
   "en-US" [900000000000509007]})



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
  "Generate a locale matching function to return the best refsets to use given a
  'language-priority-list'.
  Parameters:
   - store : a SNOMED store

  Returns:
   - a function that takes a single string containing a list of comma-separated
   language ranges or a list of language ranges in the form of the
   \"Accept-Language\" header defined in RFC 2616.

   This closes over the installed reference sets at the time of function
   generation and so does not take into account of changes made since
   it was generated."
  [store]
  (let [installed-refsets (store/get-installed-reference-sets store)
        installed-language-reference-sets (filter-language-reference-sets language-reference-sets installed-refsets )]
    (partial do-match installed-language-reference-sets)))

(defn match
  "Convenience method to match a language-priority-list to the installed
  language reference sets in the store specified."
  [store language-priority-list]
  ((match-fn store) language-priority-list))