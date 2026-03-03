; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.cmd.mcp
  "A native MCP (Model Context Protocol) server for hermes.
  Communicates via stdio using newline-delimited JSON-RPC 2.0."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.mcp :as mcp])
  (:import (java.io BufferedReader InputStreamReader PrintStream)))

(set! *warn-on-reflection* true)

(defn- json-rpc-response [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn- json-rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- write-message! [msg]
  (let [s (json/write-str msg)]
    (.write *out* ^String s)
    (.write *out* "\n")
    (.flush *out*)))

(def server-info
  {"protocolVersion" "2024-11-05"
   "capabilities"    {"tools"     {}
                      "resources" {}
                      "prompts"   {}}
   "serverInfo"      {"name"    "hermes"
                      "version" (try (:version (edn/read-string (slurp (io/resource "version.edn"))))
                                     (catch Exception _ "unknown"))}})

(defn- handle-initialize [id _params]
  (json-rpc-response id server-info))

(defn- handle-ping [id _params]
  (json-rpc-response id {}))

(defn- handle-tools-list [id _params]
  (json-rpc-response id {"tools" (mcp/tools)}))

(defn- handle-resources-list [id _params]
  (json-rpc-response id {"resources" (mcp/resources)}))

(defn- handle-resources-read [id {:strs [uri]}]
  (try
    (json-rpc-response id
                       {"contents" [{"uri"      uri
                                     "mimeType" "text/plain"
                                     "text"     (mcp/resource-content uri)}]})
    (catch Exception e
      (json-rpc-response id
                         {"contents" [{"uri"      uri
                                       "mimeType" "text/plain"
                                       "text"     (str "Error: " (ex-message e))}]}))))

(defn- handle-prompts-list [id _params]
  (json-rpc-response id {"prompts" (mcp/prompts)}))

(defn- handle-prompts-get [id {:strs [name arguments]}]
  (try
    (let [{:keys [description messages]} (mcp/get-prompt name (or arguments {}))]
      (json-rpc-response id {"description" description "messages" messages}))
    (catch Exception e
      (json-rpc-error id -32602 (ex-message e)))))

(defn- handle-tools-call [svc id {:strs [name arguments]}]
  (try
    (let [result (mcp/call-tool svc name (update-keys (or arguments {}) keyword))]
      (json-rpc-response id
                         {"content" [{"type" "text"
                                      "text" (json/write-str result)}]}))
    (catch Exception e
      (json-rpc-response id
                         {"content" [{"type" "text"
                                      "text" (str "Error: " (ex-message e))}]
                          "isError" true}))))

(defn dispatch [svc {:strs [method id params]}]
  (case method
    "initialize"                (handle-initialize id params)
    "ping"                      (handle-ping id params)
    "tools/list"                (handle-tools-list id params)
    "tools/call"                (handle-tools-call svc id params)
    "resources/list"            (handle-resources-list id params)
    "resources/read"            (handle-resources-read id params)
    "prompts/list"              (handle-prompts-list id params)
    "prompts/get"               (handle-prompts-get id params)
    "notifications/initialized" nil
    "notifications/cancelled"   nil
    (when id
      (json-rpc-error id -32601 (str "Method not found: " method)))))

(defn start!
  "Start the MCP server, reading JSON-RPC messages from stdin and writing
  responses to stdout. Logs to stderr. Blocks until stdin is closed."
  [svc]
  (log/info "starting MCP server")
  (let [out-stream System/out
        rdr (BufferedReader. (InputStreamReader. System/in))]
    ;; Redirect System/out to stderr so stray prints don't corrupt the JSON-RPC stream.
    (System/setOut (PrintStream. System/err true))
    (try
      (binding [*out* (java.io.OutputStreamWriter. out-stream)]
        (loop []
          (when-let [line (.readLine rdr)]
            (when-not (str/blank? line)
              (try
                (let [msg (json/read-str line)]
                  (when-let [response (dispatch svc msg)]
                    (write-message! response)))
                (catch Exception e
                  (log/error e "error processing MCP message")
                  (try
                    (write-message! (json-rpc-error nil -32700 "Parse error"))
                    (catch Exception _)))))
            (recur))))
      (finally
        (System/setOut (PrintStream. out-stream true))))))
