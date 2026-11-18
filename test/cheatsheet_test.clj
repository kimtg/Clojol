(ns cljl.cheatsheet-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.main :as main]
            [clojure.data :as data]
            [clojure.datafy :as datafy]
            [clojure.pprint :as pprint]
            [clojure.repl :as repl]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.spec.alpha :as s]
            [clojure.math :as math]
            [clojure.data.priority-map :as pm]
            [clojure.data.int-map :as im]
            [clojure.data.avl :as avl]
            [flatland.ordered.map :as om]
            [flatland.ordered.set :as os]
            [flatland.useful.map :as um]
            [clojure.core.async :as async]))

(deftest dynamic-vars-and-print-test
  (testing "version and tagged literals"
    (is (map? *clojure-version*))
    (is (string? (clojure-version)))
    (is (uuid? #uuid "550e8400-e29b-41d4-a716-446655440000"))
    (is (inst? #inst "2020-01-02T03:04:05Z"))
    (is (contains? default-data-readers 'uuid))
    (is (contains? default-data-readers 'inst)))
  (testing "*print-length*, *print-level*, *print-meta*"
    (is (= "[1 2 ...]" (binding [*print-length* 2] (pr-str [1 2 3 4]))))
    (is (= "[[#]]" (binding [*print-level* 2] (pr-str [[[1]]]))))
    (is (str/includes? (binding [*print-meta* true] (pr-str ^:foo [1])) ":foo"))))

(deftest predicates-math-parsing-test
  (testing "predicates & casts"
    (is (any? nil))
    (is (bytes? (byte-array 3)))
    (is (ident? :a))
    (is (ident? 'foo/bar))
    (is (simple-ident? :a))
    (is (qualified-ident? :a/b))
    (is (indexed? [1 2]))
    (is (infinite? ##Inf))
    (is (NaN? ##NaN))
    (is (uri? (java.net.URI. "https://example.com")))
    (is (map-entry? (first {:a 1})))
    (is (reversible? [1 2 3]))
    (is (= 5 (cast Long 5))))
  (testing "parsing and math"
    (is (= true (parse-boolean "true")))
    (is (= 42 (parse-long "42")))
    (is (= 3.5 (parse-double "3.5")))
    (is (uuid? (parse-uuid (str (random-uuid)))))
    (is (= 10 (byte 10)))
    (is (= 10 (short 10)))
    (is (= 10 (num 10)))
    (is (= 123N (biginteger 123)))
    (is (= 1/2 (rationalize 0.5)))
    (is (= 7 (unchecked-add 3 4)))
    (is (= 2 (unchecked-subtract 5 3)))
    (is (= 12 (unchecked-multiply 3 4)))
    (is (= 5 (unchecked-inc 4)))
    (is (= 3 (unchecked-dec 4)))
    (is (= -4 (unchecked-negate 4)))
    (is (= "abcd" (max-key count "a" "abcd" "ab")))
    (is (= "a" (min-key count "abcd" "a" "ab")))
    (is (some? (with-precision 10 (/ 1M 3M)))))
  (testing "reflection & exceptions"
    (is (seq (bases String)))
    (is (seq (supers String)))
    (is (= "boom" (ex-message (ex-info "boom" {:k 1}))))
    (let [cause (RuntimeException. "root")
          outer (ex-info "top" {} cause)
          m (Throwable->map outer)]
      (is (= cause (ex-cause outer)))
      (is (vector? (:via m)))
      (is (vector? (StackTraceElement->vec (aget (.getStackTrace outer) 0)))))
    (let [b (bean (java.util.Date. 0))]
      (is (= 0 (:time b))))))

(deftest arrays-test
  (testing "typed arrays, aset-*, amap, areduce"
    (let [ia (int-array [1 2 3])]
      (is (= 3 (alength ia)))
      (aset-int ia 1 20)
      (is (= 20 (aget (ints ia) 1)))
      (is (= 24 (areduce ia idx ret 0 (+ ret (aget ia idx)))))
      (let [doubled (amap ia idx ret (int (* 2 (aget ia idx))))]
        (is (= [2 40 6] (vec (seq doubled))))))
    (is (= 2 (alength (boolean-array [true false]))))
    (is (= 2 (alength (short-array [1 2]))))
    (is (= 2 (alength (float-array [1.0 2.0]))))
    (is (= 2 (alength (make-array String 2))))
    (is (= 2 (alength (into-array String ["a" "b"]))))
    (is (= 2 (alength (to-array-2d [[1 2] [3 4]]))))))

(deftest sorted-collections-test
  (testing "sorted-map, sorted-set, subseq, rsubseq, vector-of"
    (let [sm (sorted-map :c 3 :a 1 :b 2)]
      (is (sorted? sm))
      (is (reversible? sm))
      (is (= [[:a 1] [:b 2] [:c 3]] (vec sm)))
      (is (= [[:c 3] [:b 2] [:a 1]] (vec (rseq sm))))
      (is (= [[:b 2] [:c 3]] (vec (subseq sm >= :b))))
      (is (= [[:b 2] [:a 1]] (vec (rsubseq sm <= :b)))))
    (let [ss (sorted-set-by > 1 3 2)]
      (is (= [3 2 1] (vec ss)))
      (is (= 2 (ss 2))))
    (is (= [1 2 3] (vec (vector-of :int 1 2 3))))
    (is (= [1 2 3] (sort (comparator <) [2 3 1])))
    (is (= [3 2 1] (sort (comparator >) [2 3 1])))))

(deftest transducers-and-seqs-test
  (testing "transducers"
    (is (= [2 6] (into [] (comp (filter odd?) (map #(* 2 %))) [1 2 3 4])))
    (is (= 8 (transduce (comp (filter odd?) (map #(* 2 %))) + [1 2 3 4])))
    (is (= [1 2 3 4] (into [] cat [[1 2] [3 4]])))
    (is (= :stopped (transduce (halt-when #{3} (fn [_ _] :stopped)) conj [1 2 3 4])))
    (is (= [2 4] (vec (sequence (map inc) (filter odd? [0 1 2 3])))))
    (is (= [1 2 3] (vec (eduction (distinct) [1 1 2 3 2]))))
    (is (= [1 2 3] (into [] (dedupe) [1 1 2 2 3 3])))
    (is (= [1 :x 2 :x 3] (into [] (interpose :x) [1 2 3])))
    (is (= [[1 2] [3 4] [5]] (into [] (partition-all 2) [1 2 3 4 5])))
    (is (= [[1 3] [2 4]] (into [] (partition-by odd?) [1 3 2 4])))
    (is (= [:a 2 :c] (replace {1 :a 3 :c} [1 2 3])))
    (is (= [:a 2 :c] (into [] (replace {1 :a 3 :c}) [1 2 3]))))
  (testing "seq helpers"
    (is (= nil (not-empty [])))
    (is (= [1] (not-empty [1])))
    (is (= 3 (bounded-count 10 [1 2 3])))
    (is (= [1 2 3 4] (lazy-cat [1 2] [3 4])))
    (is (= {:A 2 :B 3} (update-vals (update-keys {:a 1 :b 2} (comp keyword str/upper-case name)) inc)))
    (is (= ["a" "b"] (line-seq (java.io.BufferedReader. (java.io.StringReader. "a\nb")))))
    (is (= [1 2 3] (iterator-seq (.iterator [1 2 3]))))
    (is (= [1 2 3] (enumeration-seq (java.util.Collections/enumeration [1 2 3]))))
    (is (= [10 20] (vec (pcalls #(+ 5 5) #(* 4 5)))))
    (is (= [10 20] (vec (pvalues (+ 5 5) (* 4 5)))))))

(deftest hierarchies-and-multimethods-test
  (testing "hierarchies"
    (let [h (-> (make-hierarchy)
                (derive ::dog ::mammal)
                (derive ::cat ::mammal)
                (derive ::mammal ::animal))]
      (is (isa? h ::dog ::animal))
      (is (contains? (parents h ::dog) ::mammal))
      (is (contains? (ancestors h ::dog) ::animal))
      (is (contains? (descendants h ::animal) ::dog))
      (let [h2 (underive h ::dog ::mammal)]
        (is (not (isa? h2 ::dog ::animal))))))
  (testing "prefer-method, prefers, remove-all-methods"
    (let [m (defmulti test-mm (fn [x y] [x y]))]
      (defmethod test-mm [::a ::b] [_ _] :ab)
      (defmethod test-mm [::c ::d] [_ _] :cd)
      (prefer-method test-mm [::a ::b] [::c ::d])
      (is (map? (prefers test-mm)))
      (remove-all-methods test-mm)
      (is (empty? (methods test-mm))))))

(defn redef-target [x] (* 2 x))

(deftest namespaces-vars-meta-test
  (testing "ns queries and vars"
    (is (= 'clojure.core (ns-name (the-ns 'clojure.core))))
    (is (var? (ns-resolve 'clojure.core 'inc)))
    (is (var? (requiring-resolve 'clojure.string/upper-case)))
    (let [a (atom 1)]
      (alter-meta! a assoc :tag :v1)
      (is (= :v1 (:tag (meta a))))
      (reset-meta! a {:tag :v2})
      (is (= :v2 (:tag (meta a)))))
    (with-local-vars [lv 10]
      (is (= 10 @lv))
      (var-set lv 20)
      (is (= 20 @lv)))
    (let [f (fn [] (redef-target 5))]
      (is (= 10 (f)))
      (with-redefs [redef-target #(+ % 100)]
        (is (= 105 (f)))))
    (let [lk (Object.)]
      (is (= :ok (locking lk :ok))))))

(deftest concurrency-test
  (testing "tap>"
    (let [seen (atom [])
          listener (fn [v] (swap! seen conj v))]
      (add-tap listener)
      (tap> :hello)
      (Thread/sleep 40)
      (remove-tap listener)
      (is (= [:hello] @seen))))
  (testing "agents"
    (let [ag (agent 10)]
      (send ag + 5)
      (await ag)
      (is (= 15 @ag))
      (set-error-mode! ag :continue)
      (is (= :continue (error-mode ag)))
      (send ag (fn [_] (throw (RuntimeException. "err"))))
      (await ag)
      (is (= 15 @ag))
      (set-error-mode! ag :fail)
      (send ag (fn [_] (throw (RuntimeException. "fail-err"))))
      (await-for 500 ag)
      (is (some? (agent-error ag)))
      (restart-agent ag 99)
      (is (= 99 @ag))
      (is (nil? (agent-error ag)))))
  (testing "STM refs"
    (let [r1 (ref 100)
          r2 (ref 200)]
      (dosync
        (alter r1 - 25)
        (commute r2 + 25)
        (ensure r1))
      (is (= 75 @r1))
      (is (= 225 @r2))
      (dosync (ref-set r1 10))
      (is (= 10 @r1)))))

(defprotocol Greeter
  (greet [this who]))

(defrecord Person [name age]
  Greeter
  (greet [this who] (str name " greets " who)))

(deftype Counter [val]
  clojure.lang.IDeref
  (deref [this] val))

(deftest protocols-records-reify-proxy-test
  (testing "defprotocol + defrecord + extend-type + reify"
    (let [p (->Person "Alice" 30)]
      (is (record? p))
      (is (satisfies? Greeter p))
      (is (extends? Greeter Person))
      (is (= "Alice" (:name p)))
      (is (= "Alice greets Bob" (greet p "Bob")))
      (is (= 31 (:age (assoc p :age 31)))))
    (extend-type String
      Greeter
      (greet [s who] (str s " -> " who)))
    (is (= "Hi -> Tim" (greet "Hi" "Tim")))
    (let [r (reify Greeter
              (greet [_ who] (str "reify:" who)))]
      (is (= "reify:Zoe" (greet r "Zoe"))))
    (let [c (->Counter 42)]
      (is (= 42 @c))))
  (testing "proxy"
    (let [px (proxy [Object] []
               (toString [] "proxied-obj"))]
      (is (= "proxied-obj" (str px))))))

(deftest stdlib-additions-test
  (testing "clojure.string, set, walk, edn"
    (is (= "a&lt;b" (str/escape "a<b" {\< "&lt;"})))
    (is (= "\\$1" (str/re-quote-replacement "$1")))
    (is (= #{1 2 3} (set/union #{1 2} #{2 3})))
    (is (= #{{:a 1 :b 10 :c 100}}
           (set/join #{{:a 1 :b 10}} #{{:b 10 :c 100}})))
    (is (= #{{:A 1 :B 2}} (set/rename #{{:a 1 :b 2}} {:a :A :b :B})))
    (is (= #{{:a 1}} (set/project #{{:a 1 :b 2}} [:a])))
    (is (= {{:a 1} #{{:a 1 :b 2}}} (set/index #{{:a 1 :b 2}} [:a])))
    (is (= [:x [:y]] (walk/postwalk-replace {1 :x 2 :y} [1 [2]])))
    (is (= [:x [:y]] (walk/prewalk-replace {1 :x 2 :y} [1 [2]])))
    (is (= {:a 1} (edn/read-string "{:a 1}")))
    (is (= {:a 1} (edn/read (java.io.PushbackReader. (java.io.StringReader. "{:a 1}"))))))
  (testing "clojure.java.io & shell"
    (let [tmp (java.io.File/createTempFile "cljl-test" ".txt")]
      (try
        (io/copy "hello-io" tmp)
        (is (= "hello-io" (slurp tmp)))
        (is (= "hello-io" (slurp (io/input-stream tmp))))
        (is (= (.getPath tmp) (.getPath (io/as-file (.toURI tmp)))))
        (finally (io/delete-file tmp true))))
    (is (= "rel/path" (str/replace (io/as-relative-path "rel/path") "\\" "/")))
    (let [res (sh/sh "java" "-version")]
      (is (= 0 (:exit res)))))
  (testing "clojure.repl, main, data, datafy, pprint, xml, zip"
    (is (some #(= 'clojure.string/upper-case %) (repl/apropos "upper-case")))
    (let [ex (ex-info "triage-me" {})
          tr (main/ex-triage ex)]
      (is (str/includes? (main/ex-str tr) "triage-me"))
      (is (str/includes? (main/err->msg ex) "triage-me")))
    (is (= [{:a 1} {:a 2} {:b 3}] (data/diff {:a 1 :b 3} {:a 2 :b 3})))
    (is (= 42 (datafy/nav [42] 0 42)))
    (is (= "  5" (pprint/cl-format nil "~3d" 5)))
    (is (= "hi\n" (str/replace (with-out-str (pprint/pprint 'hi)) "\r\n" "\n")))
    (let [parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes "<root id=\"1\"><child>text</child></root>" "UTF-8")))]
      (is (= :root (:tag parsed)))
      (is (= "1" (get-in parsed [:attrs :id]))))
    (let [z (zip/vector-zip [1 [2 3] 4])]
      (is (= 1 (-> z zip/down zip/node)))
      (is (= 20 (-> z zip/down zip/right zip/down (zip/edit * 10) zip/root second first)))
      (is (= 30 (-> z zip/down zip/right zip/down zip/right (zip/edit * 10) zip/root second second))))))

(deftest spec-and-contrib-test
  (testing "clojure.spec.alpha"
    (s/def ::num (s/and int? pos?))
    (s/def ::either (s/or :n ::num :s string?))
    (s/def ::rec (s/keys :req [::num] :opt [::either]))
    (is (s/valid? ::num 5))
    (is (not (s/valid? ::num -1)))
    (is (= [:n 5] (s/conform ::either 5)))
    (is (s/valid? ::rec {::num 3 ::either "ok"}))
    (is (not (s/valid? ::rec {})))
    (is (some? (s/explain-data ::num -1)))
    (is (string? (s/explain-str ::num -1)))
    (let [re (s/cat :k keyword? :vals (s/+ int?))]
      (is (= {:k :a :vals [1 2 3]} (s/conform re [:a 1 2 3])))))
  (testing "priority-map, int-map, avl, flatland"
    (let [p (pm/priority-map :a 3 :b 1 :c 2)]
      (is (= [:b 1] (peek p)))
      (is (= [:c 2] (peek (pop p))))
      (is (= 3 (p :a))))
    (let [m (im/int-map 2 "two" 1 "one")
          s (im/int-set [3 1 2])]
      (is (= [1 2] (keys m)))
      (is (= [1 2 3] (vec s)))
      (is (= #{1 2} (disj (im/dense-int-set 1 2 3) 3))))
    (is (= [1 2 3] (keys (avl/sorted-map 3 :c 1 :a 2 :b))))
    (let [o (om/ordered-map :z 1 :a 2 :m 3)
          os (os/ordered-set :z :a :m)]
      (is (= [:z :a :m] (keys o)))
      (is (= [:z :a :m] (vec os)))
      (is (neg? ((um/ordering-map [:b :a]) :b :a)))))
  (testing "clojure.core.async"
    (let [c (async/chan 2)
          g (async/go
              (async/>! c :msg1)
              (async/>! c :msg2)
              (async/close! c)
              :done)]
      (is (= :done (async/<!! g)))
      (is (= :msg1 (async/<!! c)))
      (is (= :msg2 (async/<!! c)))
      (is (= nil (async/<!! c))))
    (let [c1 (async/chan 1)
          c2 (async/chan 1)]
      (async/>!! c2 :from-c2)
      (is (= [:from-c2 c2] (async/alts!! [c1 c2])))
      (is (nil? (async/<!! (async/timeout 10)))))))

(deftest clojure-math-test
  (testing "clojure.math public API completeness (46 vars)"
    (let [pubs (ns-publics 'clojure.math)
          expected '[E PI IEEE-remainder acos add-exact asin atan atan2 cbrt ceil
                     copy-sign cos cosh decrement-exact exp expm1 floor floor-div
                     floor-mod get-exponent hypot increment-exact log log10 log1p
                     multiply-exact negate-exact next-after next-down next-up pow
                     random rint round scalb signum sin sinh sqrt subtract-exact
                     tan tanh to-degrees to-int-exact to-radians ulp]]
      (is (= 46 (count pubs)))
      (doseq [sym expected]
        (is (contains? pubs sym) (str "Missing from clojure.math: " sym)))))
  (testing "constants and trig/exp/log/pow/root"
    (is (< (abs (- math/E 2.718281828459045)) 1e-15))
    (is (< (abs (- math/PI 3.141592653589793)) 1e-15))
    (is (< (abs (math/sin 0.0)) 1e-15))
    (is (< (abs (- (math/sin (/ math/PI 2.0)) 1.0)) 1e-15))
    (is (< (abs (- (math/cos 0.0) 1.0)) 1e-15))
    (is (< (abs (math/tan 0.0)) 1e-15))
    (is (< (abs (- (math/asin 1.0) (/ math/PI 2.0))) 1e-15))
    (is (< (abs (math/acos 1.0)) 1e-15))
    (is (< (abs (- (math/atan 1.0) (/ math/PI 4.0))) 1e-15))
    (is (< (abs (- (math/atan2 1.0 1.0) (/ math/PI 4.0))) 1e-15))
    (is (< (abs (- (math/to-radians 180) math/PI)) 1e-15))
    (is (< (abs (- (math/to-degrees math/PI) 180.0)) 1e-15))
    (is (< (abs (- (math/sinh 0.0) 0.0)) 1e-15))
    (is (< (abs (- (math/cosh 0.0) 1.0)) 1e-15))
    (is (< (abs (- (math/tanh 0.0) 0.0)) 1e-15))
    (is (< (abs (- (math/exp 1.0) math/E)) 1e-15))
    (is (< (abs (- (math/expm1 0.0) 0.0)) 1e-15))
    (is (< (abs (- (math/log math/E) 1.0)) 1e-15))
    (is (< (abs (- (math/log10 1000) 3.0)) 1e-15))
    (is (< (abs (- (math/log1p 0.0) 0.0)) 1e-15))
    (is (= 5.0 (math/sqrt 25)))
    (is (= 3.0 (math/cbrt 27)))
    (is (= 8.0 (math/pow 2 3)))
    (is (= 5.0 (math/hypot 3 4))))
  (testing "rounding, IEEE, float bit/exponent ops, random"
    (is (= -1.0 (math/IEEE-remainder 11.0 4.0)))
    (is (= 5.0 (math/ceil 4.2)))
    (is (= 4.0 (math/floor 4.8)))
    (is (= 4.0 (math/rint 4.5)))
    (is (= 5 (math/round 4.5)))
    (is (= -5.0 (math/copy-sign 5.0 -2.0)))
    (is (= 1.0 (math/signum 42)))
    (is (= -1.0 (math/signum -42)))
    (is (= 0.0 (math/signum 0.0)))
    (is (= 3 (math/get-exponent 8.0)))
    (is (= 8.0 (math/scalb 1.0 3)))
    (is (pos? (math/ulp 1.0)))
    (is (> (math/next-up 1.0) 1.0))
    (is (< (math/next-down 1.0) 1.0))
    (is (= (math/next-up 1.0) (math/next-after 1.0 2.0)))
    (let [r (math/random)]
      (is (and (<= 0.0 r) (< r 1.0)))))
  (testing "exact integer and floor div/mod operations"
    (is (= 5 (math/add-exact 2 3)))
    (is (= 4 (math/subtract-exact 7 3)))
    (is (= 12 (math/multiply-exact 3 4)))
    (is (= 6 (math/increment-exact 5)))
    (is (= 4 (math/decrement-exact 5)))
    (is (= -5 (math/negate-exact 5)))
    (is (= 42 (math/to-int-exact 42)))
    (is (= -3 (math/floor-div -7 3)))
    (is (= 2 (math/floor-mod -7 3)))
    (is (thrown? ArithmeticException (math/add-exact Long/MAX_VALUE 1)))
    (is (thrown? ArithmeticException (math/subtract-exact Long/MIN_VALUE 1)))
    (is (thrown? ArithmeticException (math/multiply-exact Long/MAX_VALUE 2)))
    (is (thrown? ArithmeticException (math/increment-exact Long/MAX_VALUE)))
    (is (thrown? ArithmeticException (math/decrement-exact Long/MIN_VALUE)))
    (is (thrown? ArithmeticException (math/negate-exact Long/MIN_VALUE)))
    (is (thrown? ArithmeticException (math/to-int-exact (inc Integer/MAX_VALUE))))))

(let [res (run-tests 'cljl.cheatsheet-test)]
  (when (or (pos? (:fail res)) (pos? (:error res)))
    (System/exit 1)))
