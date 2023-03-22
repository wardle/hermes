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
(ns com.eldrix.hermes.verhoeff)

(def ^:private multiplication-table
  [[0 1 2 3 4 5 6 7 8 9]
   [1 2 3 4 0 6 7 8 9 5]
   [2 3 4 0 1 7 8 9 5 6]
   [3 4 0 1 2 8 9 5 6 7]
   [4 0 1 2 3 9 5 6 7 8]
   [5 9 8 7 6 0 4 3 2 1]
   [6 5 9 8 7 1 0 4 3 2]
   [7 6 5 9 8 2 1 0 4 3]
   [8 7 6 5 9 3 2 1 0 4]
   [9 8 7 6 5 4 3 2 1 0]])

(def ^:private permutation-table
  [[0 1 2 3 4 5 6 7 8 9]
   [1 5 7 6 2 8 3 0 9 4]
   [5 8 0 3 7 9 6 1 4 2]
   [8 9 1 6 0 4 3 5 2 7]
   [9 4 5 3 1 2 6 8 7 0]
   [4 2 8 6 5 7 3 9 0 1]
   [2 7 9 3 8 0 6 4 1 5]
   [7 0 4 6 9 1 3 2 5 8]])

(def ^:private inverse [0 4 3 2 1 5 6 7 8 9])

(defn calculate
  "Calculate a Verhoeff check digit"
  [s]
  (loop [ss (str s), ll (count ss), i 0, checksum 0]
    (if (= i ll)
      (inverse checksum)
      (let [n (- ll i 1)                                    ;; get index rightmost digit
            v (mod (- (int (nth ss n)) (int \0)) 10)        ;; get digit from string and convert to integer value
            perm (get-in permutation-table [(mod (inc i) 8) v]) ;; lookup permutation table value
            checksum (long (get-in multiplication-table [checksum perm]))]
        (recur ss ll (inc i) checksum)))))

;; The Verhoeff checksum calculation is performed as follows:
;; Create an array n out of the individual digits of the number, taken from right to left (rightmost digit is n0, etc.).
;; Initialize the checksum c to zero.
;; For each index i of the array n, starting at zero, replace c with d(c, p(i mod 8, ni)).
;; The original number is valid if and only if c = 0.
(defn valid?
  "Checks whether Verhoeff check digit is correct."
  [s]
  (loop [ss (str s), ll (count ss), i 0, checksum 0]
    (if (= i ll)
      (zero? (inverse checksum))                              ;; number is valid if checksum==0
      (let [n (- ll i 1)
            v (mod (- (int (nth ss n)) (int \0)) 10)        ;; modulus of 10, so wrap in case of non-numeric characters
            perm (get-in permutation-table [(mod i 8) v])
            checksum (long (get-in multiplication-table [checksum perm]))]
        (recur ss ll (inc i) checksum)))))

(defn append
  "Append a Verhoeff check digit."
  [s] (str s (calculate s)))

(comment
  (calculate "12345")
  (calculate "31122019000")
  (valid? "311220190006")
  (calculate "2470000")
  (valid? "24700007")
  (valid? "24700008")
  (valid? 24700007))

