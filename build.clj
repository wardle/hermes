(ns build
  (:refer-clojure :exclude [compile])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [borkdude.gh-release-artifact :as gh])
  (:import (java.time LocalDate)))

(def lib 'com.eldrix/hermes)
(def version (edn/read-string (slurp "resources/version.edn")))
(def version-str (format "%s.%s" (:version version) (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-lib-%s.jar" (name lib) version-str))
(def uber-file (format "target/%s-%s.jar" (name lib) version-str))

(def citation
  (str/join "\n"
            ["cff-version: 1.2.0"
             "message: \"If you use this software, please cite it as below.\""
             "authors:"
             "- family-names: \"Wardle\""
             "  given-names: \"Mark\""
             "  orcid: \"https://orcid.org/0000-0002-4543-7068\""
             "title: \"Hermes\""
             (str "version: " version-str)
             "doi: 10.5281/zenodo.5504046"
             (str "date-released: " (LocalDate/now))
             "url: \"https://github.com/wardle/hermes\""]))

(defn clean [_]
  (b/delete {:path "target"}))

(defn update-citation [_]
  (spit "CITATION.cff" citation))

(defn jar [_]
  (update-citation nil)
  (clean nil)
  (println "Building" jar-file)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version-str
                :basis     @jar-basis
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/wardle/hermes"
                            :tag                 (str "v" version-str)
                            :connection          "scm:git:git://github.com/wardle/hermes.git"
                            :developerConnection "scm:git:ssh://git@github.com/wardle/hermes.git"}
                :pom-data  [[:description
                             "A library and microservice implementing the health and care terminology SNOMED CT with support for cross-maps, inference, fast full-text search, autocompletion, compositional grammar and the expression constraint language."]
                            [:developers
                             [:developer
                              [:id "wardle"] [:name "Mark Wardle"] [:email "mark@wardle.org"] [:url "https://wardle.org"]]]
                            [:organization [:name "Eldrix Ltd"]]
                            [:licenses
                             [:license
                              [:name "Eclipse Public License v2.0"]
                              [:url "https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html"]
                              [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"], :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str/join "/" [class-dir "META-INF" "LICENSE"])})
  (b/jar {:class-dir class-dir, :jar-file jar-file}))

(defn install
  "Installs pom and library jar in local maven repository"
  [_]
  (jar nil)
  (println "Installing" jar-file)
  (b/install {:basis     @jar-basis
              :lib       lib
              :class-dir class-dir
              :version   version-str
              :jar-file  jar-file}))

(defn deploy
  "Deploy library to clojars.
  Environment variables CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."
  [_]
  (println "Deploying" jar-file)
  (jar nil)
  (dd/deploy {:installer :remote, :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib, :class-dir class-dir})}))

(defn- check-jvm
  []
  (let [jvm-version (-> (System/getProperty "java.specification.version") parse-long)]
    (when (>= jvm-version 21)
      (throw (ex-info "Not building with jdk >= 21 because of the SequencedCollection issue." {})))
    (println "Building with jdk" jvm-version)))

(defn uber
  "Build an executable uberjar file for HTTP server and CLI tooling.
  Options:
  - :out     - output file (default: target/hermes-<version>.jar)
  - :aliases - additional deps.edn aliases (e.g. [:lucene10])"
  [{:keys [out aliases] :or {out uber-file}}]
  (when-not (seq aliases) (check-jvm))
  (let [basis (b/create-basis {:project "deps.edn", :aliases (into [:run] aliases)})]
    (println "Building uberjar:" out)
    (update-citation nil)
    (clean nil)
    (b/copy-dir {:src-dirs ["resources"], :target-dir class-dir})
    (spit (str class-dir "/version.edn") (pr-str (assoc version :version version-str)))
    (b/copy-file {:src "cmd/logback.xml", :target (str class-dir "/logback.xml")})
    (b/copy-file {:src "LICENSE" :target (str/join "/" [class-dir "LICENSE"])})
    (b/compile-clj {:basis        basis
                    :src-dirs     ["src" "cmd"]
                    :ns-compile   ['com.eldrix.hermes.cmd.core]
                    :compile-opts {:elide-meta     [:doc :added]
                                   :direct-linking true}
                    :java-opts    ["-Dlogback.configurationFile=logback-build.xml"]
                    :class-dir    class-dir})
    (b/uber {:class-dir class-dir
             :uber-file out
             :basis     basis
             :main      'com.eldrix.hermes.cmd.core})))

(def uber-lucene10-file (format "target/%s-%s-lucene10.jar" (name lib) version-str))

(defn release
  "Deploy release to GitHub. Requires valid token in GITHUB_TOKEN environmental
   variable."
  [_]
  (uber nil)
  (println "Deploying release to GitHub")
  (gh/release-artifact {:org    "wardle"
                        :repo   "hermes"
                        :tag    (str "v" version-str)
                        :file   uber-file
                        :sha256 true}))

(defn release-lucene10
  "Build a Lucene 10 uberjar (requires JDK 21+) and upload to the same GitHub
  release. Run after 'release'. Requires GITHUB_TOKEN environment variable."
  [_]
  (uber {:out uber-lucene10-file :aliases [:lucene10]})
  (println "Uploading Lucene 10 uberjar to GitHub release")
  (gh/release-artifact {:org       "wardle"
                        :repo      "hermes"
                        :tag       (str "v" version-str)
                        :file      uber-lucene10-file
                        :sha256    true
                        :overwrite true}))
