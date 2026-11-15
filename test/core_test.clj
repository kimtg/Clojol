(ns cljl.core-test
  (:require [clojure.test :refer [deftest is are testing run-tests]]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.core.async :as async]))

(deftest arithmetic-and-numbers-test
  (testing "basic arithmetic and ratios"
    (is (= 0 (+)))
    (is (= 42 (+ 42)))
    (is (= 10 (+ 1 2 3 4)))
    (is (= 1 (*)))
    (is (= 24 (* 1 2 3 4)))
    (is (= -5 (- 5)))
    (is (= 6 (- 10 3 1)))
    (is (= 3/2 (/ 3 2)))
    (is (= 2 (/ 6 3)))
    (is (= 5/6 (+ 1/2 1/3)))
    (is (= 3 (quot 10 3)))
    (is (= 1 (rem 10 3)))
    (is (= 2 (mod -1 3)))
    (is (= 10 (inc 9)))
    (is (= 8 (dec 9)))
    (is (= 5 (abs -5)))
    (is (= 9 (max 1 9 3)))
    (is (= 1 (min 1 9 3)))
    (is (< 1 2 3 4))
    (is (<= 1 1 2 3))
    (is (> 4 3 2 1))
    (is (>= 4 4 2 1))
    (is (== 1 1.0))
    (is (not (= 1 1.0)))
    (is (zero? 0))
    (is (pos? 1))
    (is (neg? -1))
    (is (even? 4))
    (is (odd? 5)))
  (testing "bigint and overflow promotion"
    (is (integer? 9223372036854775808N))
    (is (= 9223372036854775808N (+' 9223372036854775807 1)))
    (is (= 100000000000000000000N (*' 10000000000 10000000000)))
    (is (= 3.5M (+ 1.5M 2.0M))))
  (testing "bitwise"
    (is (= 4 (bit-and 6 5)))
    (is (= 7 (bit-or 6 5)))
    (is (= 3 (bit-xor 6 5)))
    (is (= 16 (bit-shift-left 1 4)))
    (is (= 4 (bit-shift-right 16 2)))))

(deftest collections-test
  (testing "vectors"
    (let [v [1 2 3]]
      (is (vector? v))
      (is (= 3 (count v)))
      (is (= 2 (nth v 1)))
      (is (= 2 (v 1)))
      (is (= :nf (v 99 :nf)))
      (is (= [1 2 3 4] (conj v 4)))
      (is (= [1 99 3] (assoc v 1 99)))
      (is (= 3 (peek v)))
      (is (= [1 2] (pop v)))
      (is (= [2 3] (subvec v 1 3)))
      (is (= '(3 2 1) (rseq v)))))
  (testing "large persistent vector (> 1050 items across trie levels)"
    (let [v (vec (range 1500))]
      (is (= 1500 (count v)))
      (is (= 0 (nth v 0)))
      (is (= 1234 (nth v 1234)))
      (is (= 1499 (peek v)))
      (is (= 9999 (nth (assoc v 1234 9999) 1234)))
      (is (= 1234 (nth v 1234)))))
  (testing "maps"
    (let [m {:a 1 :b 2 nil :nil-val}]
      (is (map? m))
      (is (= 3 (count m)))
      (is (= 1 (:a m)))
      (is (= 1 (m :a)))
      (is (= :nil-val (get m nil)))
      (is (= :missing (get m :z :missing)))
      (is (contains? m :a))
      (is (contains? m nil))
      (is (= {:a 1 :b 2 :c 3 nil :nil-val} (assoc m :c 3)))
      (is (= {:a 1 nil :nil-val} (dissoc m :b)))
      (is (= {:a 2 :b 2 nil :nil-val} (update m :a inc)))
      (is (= [:a 1] (find m :a)))
      (is (= #{:a :b nil} (set (keys m))))))
  (testing "nested map ops"
    (let [m {:a {:b {:c 10}}}]
      (is (= 10 (get-in m [:a :b :c])))
      (is (= 99 (get-in m [:a :b :x] 99)))
      (is (= {:a {:b {:c 42}}} (assoc-in m [:a :b :c] 42)))
      (is (= {:a {:b {:c 11}}} (update-in m [:a :b :c] inc)))))
  (testing "sets"
    (let [s #{1 2 3}]
      (is (set? s))
      (is (= 3 (count s)))
      (is (contains? s 2))
      (is (= 2 (s 2)))
      (is (= #{1 2 3 4} (conj s 4)))
      (is (= #{1 3} (disj s 2))))))

(deftest sequences-and-lazy-test
  (testing "lazy infinite sequences"
    (is (= '(0 1 2 3 4) (take 5 (range))))
    (is (= '(1 2 4 8 16) (take 5 (iterate #(* 2 %) 1))))
    (is (= '(:x :x :x) (take 3 (repeat :x))))
    (is (= '(1 2 1 2 1) (take 5 (cycle [1 2]))))
    (is (= '(2 4 6 8 10) (take 5 (filter even? (iterate inc 1)))))
    (is (= '(1 4 9 16) (map #(* % %) [1 2 3 4]))))
  (testing "reducers & transducers"
    (is (= 55 (reduce + (range 1 11))))
    (is (= 100 (reduce + 45 (range 1 11))))
    (is (= 6 (reduce (fn [acc x] (if (> (+ acc x) 5) (reduced (+ acc x)) (+ acc x))) 0 [1 2 3 4 5])))
    (is (= 6 (reduce-kv (fn [acc _ v] (+ acc v)) 0 {:a 1 :b 2 :c 3})))
    (is (= [0 1 3 6 10] (vec (reductions + (range 5))))))
  (testing "sequence combinators"
    (is (= '(1 2 3 4 5 6) (concat [1 2] [3 4] '(5 6))))
    (is (= '(1 :a 2 :b) (interleave [1 2] [:a :b :c])))
    (is (= '(1 :sep 2 :sep 3) (interpose :sep [1 2 3])))
    (is (= '((1 2) (3 4)) (partition 2 [1 2 3 4 5])))
    (is (= '((1 2) (3 4) (5)) (partition-all 2 [1 2 3 4 5])))
    (is (= {true [2 4] false [1 3 5]} (group-by even? [1 2 3 4 5])))
    (is (= {:a 2 :b 1} (frequencies [:a :b :a])))
    (is (= '(1 2 3) (distinct [1 2 1 3 2 1])))
    (is (= '(1 2 1 3) (dedupe [1 1 2 2 2 1 3 3])))
    (is (= '(1 2 3 4 5) (flatten [1 [2 [3 4]] 5])))))

(deftest functions-closures-destructuring-test
  (testing "multi-arity and variadic functions"
    (let [f (fn
              ([] :zero)
              ([a] [:one a])
              ([a b & more] [:many a b more]))]
      (is (= :zero (f)))
      (is (= [:one 1] (f 1)))
      (is (= [:many 1 2 nil] (f 1 2)))
      (is (= [:many 1 2 '(3 4)] (f 1 2 3 4)))))
  (testing "closures and lexical shadowing"
    (let [make-adder (fn [x] (fn [y] (+ x y)))
          add10 (make-adder 10)]
      (is (= 15 (add10 5)))
      (let [x 100]
        (is (= 100 x))
        (is (= 15 (add10 5))))))
  (testing "loop/recur and fn/recur (deep iteration without stack overflow)"
    (is (= 5000050000
           (loop [i 100000 acc 0]
             (if (zero? i)
               acc
               (recur (dec i) (+ acc i))))))
    (let [sum-down (fn self [n acc]
                     (if (zero? n)
                       acc
                       (self (dec n) (+ acc n))))]
      (is (= 15 (sum-down 5 0))))
    (let [recur-fn (fn [n acc]
                     (if (zero? n)
                       acc
                       (recur (dec n) (+ acc n))))]
      (is (= 5000050000 (recur-fn 100000 0)))))
  (testing "destructuring"
    (let [[a b & rest :as all] [1 2 3 4]
          {:keys [x y] :or {y 99} :as m} {:x 10}
          {:strs [name]} {"name" "cljl"}]
      (is (= 1 a))
      (is (= 2 b))
      (is (= '(3 4) rest))
      (is (= [1 2 3 4] all))
      (is (= 10 x))
      (is (= 99 y))
      (is (= {:x 10} m))
      (is (= "cljl" name))))
  (testing "higher order functions"
    (is (= 11 ((comp inc #(* 2 %) +) 2 2 1)))
    (is (= [3 1] ((juxt + -) 2 1)))
    (is (= 15 ((partial + 10) 5)))
    (is (= 10 ((fnil + 0 0) nil 10)))))

(defmacro infix [a op b]
  `(~op ~a ~b))

(defmacro with-temp [sym val & body]
  `(let [~sym ~val]
     ~@body))

(deftest macros-and-control-flow-test
  (testing "syntax-quote, unquote, unquote-splicing, auto-gensym"
    (is (= 7 (infix 3 + 4)))
    (is (= 12 (with-temp x 5 (+ x 7)))))
  (testing "threading and control macros"
    (is (= 14 (-> 5 (+ 2) (* 2))))
    (is (= 12 (->> (range 5) (filter odd?) (map #(* % 3)) (reduce +))))
    (is (= nil (some-> {:a {:b nil}} :a :b inc)))
    (is (= 6 (some-> {:a {:b 5}} :a :b inc)))
    (is (= :two (case 2
                  1 :one
                  (2 3) :two
                  :other)))
    (is (= :default (case :missing :a 1 :default)))
    (is (= :b (cond false :a (= 1 1) :b :else :c)))
    (is (= "hi" (or nil false "hi" "bye")))
    (is (= false (and true false "hi"))))
  (testing "for and doseq comprehensions"
    (is (= [[0 1] [1 2]] (vec (for [x [0 1 2] :when (< x 2)] [x (inc x)]))))
    (let [a (atom [])]
      (doseq [x [1 2 3] y [:a :b]]
        (swap! a conj [x y]))
      (is (= [[1 :a] [1 :b] [2 :a] [2 :b] [3 :a] [3 :b]] @a)))))

(defmulti area :shape)
(defmethod area :circle [c] (* 3 (:r c) (:r c)))
(defmethod area :rect [r] (* (:w r) (:h r)))
(defmethod area :default [_] 0)

(deftest state-concurrency-multimethods-test
  (testing "atoms, volatiles, delays, promises, futures"
    (let [a (atom 0)]
      (swap! a + 5)
      (is (= 5 @a))
      (reset! a 10)
      (is (= 10 @a)))
    (let [v (volatile! 1)]
      (vswap! v * 7)
      (is (= 7 @v)))
    (let [cnt (atom 0)
          d (delay (swap! cnt inc) 42)]
      (is (not (realized? d)))
      (is (= 42 @d))
      (is (realized? d))
      (is (= 42 @d))
      (is (= 1 @cnt)))
    (let [p (promise)]
      (future (deliver p :done))
      (is (= :done @p)))
    (is (= '(10 20 30 40) (pmap #(* % 10) [1 2 3 4]))))
  (testing "multimethods"
    (is (= 12 (area {:shape :circle :r 2})))
    (is (= 20 (area {:shape :rect :w 4 :h 5})))
    (is (= 0 (area {:shape :unknown})))))

(deftest interop-exceptions-stdlib-test
  (testing "Java interop and exceptions"
    (let [sb (StringBuilder. "hello")]
      (.append sb " world")
      (is (= "hello world" (.toString sb))))
    (is (= 5 (Math/abs -5)))
    (is (= "caught: boom"
           (try
             (throw (IllegalArgumentException. "boom"))
             (catch IllegalArgumentException e
               (str "caught: " (.getMessage e)))))))
  (testing "clojure.string"
    (is (str/blank? "  \t\n"))
    (is (= "a,b,c" (str/join "," ["a" "b" "c"])))
    (is (= ["a" "b" "c"] (str/split "a:b:c" #":")))
    (is (= "HELLO" (str/upper-case "hello")))
    (is (= "hello" (str/trim "  hello  "))))
  (testing "clojure.set"
    (is (= #{1 2 3} (set/union #{1 2} #{2 3})))
    (is (= #{2} (set/intersection #{1 2} #{2 3})))
    (is (= #{1} (set/difference #{1 2} #{2 3}))))
  (testing "clojure.walk & clojure.edn"
    (is (= {:a {:b 2}} (walk/keywordize-keys {"a" {"b" 2}})))
    (is (= {:x [1 2 3]} (edn/read-string "{:x [1 2 3]}"))))
  (testing "with-out-str & regex"
    (is (= (str "hello 42" (System/lineSeparator)) (with-out-str (println "hello" 42))))
    (is (= ["ab12" "12"] (re-find #"ab(\d+)" "xxab12yy")))))

(deftest read-test
  (testing "read from *in* and PushbackReader/StringReader"
    (is (= ['(+ 1 2) 42 :kw]
           (with-in-str "(+ 1 2) 42 :kw"
             [(read) (read) (read)])))
    (is (= ['a '(b) 123]
           (with-in-str "a(b)123"
             [(read) (read) (read)])))
    (let [pbr (java.io.PushbackReader. (java.io.StringReader. "10(20)"))]
      (is (= [10 '(20) :eof]
             [(read pbr) (read pbr) (read pbr false :eof)])))
    (let [sr (java.io.StringReader. "foo bar")]
      (is (= ['foo 'bar :done]
             [(read sr) (read sr) (read {:eof :done} sr)]))))
  (testing "read EOF handling and options"
    (is (= :eof-err
           (try
             (with-in-str "" (read))
             (catch Exception _ :eof-err))))
    (is (= :sentinel
           (with-in-str "  ; comment only\n" (read *in* false :sentinel))))
    (is (= 2 (read-string {:read-cond :allow :features #{:cljs}} "#?(:clj 1 :cljs 2)"))))
  (testing "slurp on readers, streams, and file URIs"
    (is (= "hello" (slurp (java.io.StringReader. "hello"))))
    (is (= "bytes" (slurp (java.io.ByteArrayInputStream. (.getBytes "bytes" "UTF-8")))))
    (let [tmp (java.io.File/createTempFile "cljl-slurp" ".txt")]
      (try
        (spit tmp "file-uri-content")
        (is (= "file-uri-content" (slurp (.toString (.toURI tmp)))))
        (is (= "file-uri-content" (slurp (.toURI tmp))))
        (finally
          (.delete tmp))))))

(defn doc-test-foo "foo" [x] x)
(defn doc-test-multi
  "multi doc"
  ([a] a)
  ([a b] (+ a b))
  {:added "1.0"})
(defmacro doc-test-mac "mac doc" [x] x)

(deftest doc-test
  (let [nl (System/lineSeparator)]
    (testing "defn docstring and arglists"
      (is (= (str "-------------------------" nl
                  "cljl.core-test/doc-test-foo" nl
                  "([x])" nl
                  "  foo" nl)
             (with-out-str (is (nil? (doc doc-test-foo))))))
      (is (= (str "-------------------------" nl
                  "cljl.core-test/doc-test-foo" nl
                  "([x])" nl
                  "  foo" nl)
             (with-out-str (doc #'doc-test-foo)))))
    (testing "multi-arity defn with attr-map"
      (is (= "1.0" (:added (meta #'doc-test-multi))))
      (is (= (str "-------------------------" nl
                  "cljl.core-test/doc-test-multi" nl
                  "([a] [a b])" nl
                  "  multi doc" nl)
             (with-out-str (doc doc-test-multi)))))
    (testing "defmacro, special forms, and clojure.repl"
      (is (= (str "-------------------------" nl
                  "cljl.core-test/doc-test-mac" nl
                  "([x])" nl
                  "Macro" nl
                  "  mac doc" nl)
             (with-out-str (doc doc-test-mac))))
      (is (= (str "-------------------------" nl
                  "clojure.core/doc" nl
                  "([name])" nl
                  "Macro" nl
                  "  Prints documentation for a var or special form given its name" nl)
             (with-out-str (doc doc))))
      (is (= (str "-------------------------" nl
                  "if" nl
                  "Special Form" nl
                  "  Evaluates test. If not nil or false, evaluates and yields then, otherwise else or nil." nl)
             (with-out-str (doc if))))
      (require '[clojure.repl :as repl])
      (is (some? (resolve 'repl/doc))))))

(defrecord P2Rec [a b])

(deftest phase2-test
  (testing "Transients (HAMT, Vector, Set)"
    (let [t (transient {})
          t (reduce (fn [m i] (assoc! m i (* i i))) t (range 1500))
          _ (is (= 1500 (count t)))
          _ (is (= 100 (t 10)))
          t (dissoc! t 0 1 2 500 1000)
          _ (is (= 1495 (count t)))
          m (persistent! t)]
      (is (= 1495 (count m)))
      (is (= 9 (get m 3)))
      (is (nil? (get m 500)))
      (is (= :err (try (assoc! t :x 1) :no-err (catch IllegalAccessError _ :err)))))
    (let [tv (assoc! (transient [1 2 3]) 0 10 2 30)
          _ (is (= 10 (tv 0)))
          _ (is (= 30 (get tv 2)))
          pv (persistent! (pop! (conj! tv 40)))]
      (is (= [10 2 30] pv)))
    (let [ts (disj! (conj! (conj! (transient #{1 2}) 3) 4) 1 3)]
      (is (= #{2 4} (persistent! ts))))
    (let [tm (conj! (conj! (transient {:a 1}) (subvec [:b 2] 0 2)) nil)]
      (is (= {:a 1 :b 2} (persistent! tm))))
    (let [tm (transient {:a 1})
          res (deref (future (try (assoc! tm :b 2) :no-err (catch IllegalAccessError _ :thread-err))))]
      (is (= :thread-err res))
      (is (= {:a 1} (persistent! tm)))))

  (testing "Equivalence and hashing"
    (is (= (hash {:a 1 :b 2}) (hash (P2Rec. 1 2))))
    (is (= (hash {:a 1 :b 2}) (hash (sorted-map :a 1 :b 2))))
    (is (= (hash #{1 2 3}) (hash (sorted-set 1 2 3))))
    (is (false? (= (sorted-map 1 :a) (sorted-map :x :a))))
    (is (false? (= (sorted-set 1) (sorted-set :x))))
    (is (true? (distinct? 1 2 3)))
    (is (false? (distinct? 1 (Integer/valueOf 1))))
    (is (= [1 [1 2]] (distinct [1 (Integer/valueOf 1) [1 2] '(1 2)])))
    (is (= [1 [1 2]] (into [] (distinct) [1 (Integer/valueOf 1) [1 2] '(1 2)])))
    (is (= {1 2} (frequencies [1 (Integer/valueOf 1)])))
    (is (= {[1 2] [[1 2] '(1 2)]} (group-by identity [[1 2] '(1 2)]))))

  (testing "Java interop, proxy, and reify"
    (let [sb (StringBuilder.)]
      (.append sb 123)
      (.append sb "abc")
      (is (= "123abc" (.toString sb))))
    (let [ul (java.util.List/of 1 2 3)]
      (is (= 3 (.size ul)))
      (is (= [1 2 3] (vec ul))))
    (let [arr (into-array Object [3 1 4 1 5])]
      (java.util.Arrays/sort arr (fn [a b] (- b a)))
      (is (= [5 4 3 1 1] (vec arr))))
    (let [p (reify java.util.function.Predicate
              (test [_ x] (when (> x 5) :yes)))
          neg (.negate p)]
      (is (true? (.test p 10)))
      (is (false? (.test p 3)))
      (is (true? (.test neg 3)))
      (is (false? (.test neg 10))))
    (let [o (proxy [Object] [] (toString [] "custom-proxy"))]
      (is (= "custom-proxy" (.toString o)))))

  (testing "Reader, streaming eval, destructuring, case"
    (is (= :err (try (read-string "08") :no-err (catch Exception _ :err))))
    (is (= 123M (read-string "123M")))
    (is (= 0.015M (read-string "1.5e-2M")))
    (is (= [:clojure.string/x "a-b"]
           (try
             (load-string "(ns cljl.stream-test (:require [clojure.string :as s])) [::s/x (s/join \"-\" [\"a\" \"b\"])]")
             (finally
               (in-ns 'cljl.core-test)))))
    (let [f (fn [& {:keys [a b] :or {b false}}] [a b])]
      (is (= [1 false] (f :a 1)))
      (is (= [1 2] (f :a 1 {:b 2}))))
    (let [{:my.ns/keys [x] :keys [:a/y]} {:my.ns/x 10 :a/y 20}]
      (is (= [10 20] [x y])))
    (is (= :matched (case (Integer/valueOf 2) 1 :one 2 :matched :other)))
    (is (= :vec (case '(1 2) [1 2] :vec :other))))

  (testing "core.async alts!! with put port and :default"
    (let [c (async/chan 1)]
      (is (= [true c] (async/alts!! [[c :hello]])))
      (is (= :hello (async/<!! c)))
      (is (= [:fallback :default] (async/alts!! [c] :default :fallback)))
      (async/close! c)
      (is (false? (async/put! c :x))))))

(defmacro p3-mac "p3 macro" [x] `(+ 1 ~x))

(deftest phase3-test
  (testing "Source :file and :line metadata and clojure.repl/source-fn"
    (let [m (meta #'doc-test-foo)
          mm (meta #'p3-mac)]
      (is (string? (:file m)))
      (is (pos? (:line m)))
      (is (string? (:file mm)))
      (is (pos? (:line mm))))
    (let [tmp (java.io.File/createTempFile "cljl-src" ".clj")
          path (.getAbsolutePath tmp)]
      (try
        (spit tmp "(ns cljl.src-tmp)\n\n(defn tmp-adder\n  \"adds two\"\n  [a b]\n  (+ a b))\n")
        (load-file path)
        (let [v (find-var 'cljl.src-tmp/tmp-adder)
              vm (meta v)]
          (is (= path (:file vm)))
          (is (= 3 (:line vm)))
          (let [src (in-ns 'cljl.src-tmp)]
            (try
              (is (= "(defn tmp-adder\n  \"adds two\"\n  [a b]\n  (+ a b))"
                     ((resolve 'clojure.repl/source-fn) 'tmp-adder)))
              (finally
                (in-ns 'cljl.core-test)))))
        (finally
          (.delete tmp)))))

  (testing "Compile error messages include file and line context"
    (let [msg (try
                (load-string "\n\n(+ 1 nonexistent-var-xyz)")
                nil
                (catch Exception e (.getMessage e)))]
      (is (some? msg))
      (is (clojure.string/includes? msg "<load-string>:3"))))

  (testing "BytecodeJIT compiles case, locking, monitor, set!, and numeric constants"
    (let [f-case (fn [x]
                   (case x
                     1 :one
                     (2 3) :two-or-three
                     [4 5] :vec
                     :default))
          f-case-no-def (fn [x]
                          (case x
                            :a 10
                            :b 20))
          f-lock (fn [lk a]
                   (locking lk
                     (locking lk
                       (swap! a inc))))
          f-lock-ex (fn [lk]
                      (locking lk
                        (throw (IllegalStateException. "boom"))))
          f-mon (fn [obj]
                  (monitor-enter obj)
                  (try
                    :entered
                    (finally
                      (monitor-exit obj))))
          f-mon-plain (fn [obj]
                        (monitor-enter obj)
                        (monitor-exit obj)
                        :ok)
          f-arith (fn [x] (+ 1000000 (* 3 x) 1.5))]
      (is (true? (cljl.BytecodeJIT/canCompile f-case)))
      (is (some? (cljl.BytecodeJIT/compile f-case)))
      (is (true? (cljl.BytecodeJIT/canCompile f-case-no-def)))
      (is (some? (cljl.BytecodeJIT/compile f-case-no-def)))
      (is (true? (cljl.BytecodeJIT/canCompile f-lock)))
      (is (some? (cljl.BytecodeJIT/compile f-lock)))
      (is (true? (cljl.BytecodeJIT/canCompile f-lock-ex)))
      (is (some? (cljl.BytecodeJIT/compile f-lock-ex)))
      (is (true? (cljl.BytecodeJIT/canCompile f-mon-plain)))
      (is (some? (cljl.BytecodeJIT/compile f-mon-plain)))
      (is (true? (cljl.BytecodeJIT/canCompile f-arith)))
      (is (some? (cljl.BytecodeJIT/compile f-arith)))

      (let [lk (Object.)
            a (atom 0)]
        (dotimes [_ 25]
          (is (= :one (f-case 1)))
          (is (= :two-or-three (f-case 2)))
          (is (= :two-or-three (f-case (Integer/valueOf 3))))
          (is (= :vec (f-case '(4 5))))
          (is (= :default (f-case :missing)))
          (is (= 10 (f-case-no-def :a)))
          (is (= :err (try (f-case-no-def :z) :no (catch IllegalArgumentException _ :err))))
          (f-lock lk a)
          (is (= :boom (try (f-lock-ex lk) :no (catch IllegalStateException _ :boom))))
          (is (= :entered (f-mon lk)))
          (is (= :ok (f-mon-plain lk)))
          (is (= 1000013.5 (f-arith 4))))
        (is (= 25 @a))))))

(defprotocol P4Proto
  (p4-a [this])
  (p4-b [this]))

(definterface P4Counter
  (incAndGet [])
  (getVal []))

(deftype P4MutCell [^:volatile-mutable cnt]
  P4Counter
  (incAndGet [this]
    (set! cnt (inc cnt))
    cnt)
  (getVal [this]
    (let [cnt-local (+ cnt 0)]
      cnt-local)))

(deftest phase4-test
  (testing "clojure.test thrown?, thrown-with-msg?, and are"
    (is (thrown? IllegalArgumentException (throw (IllegalArgumentException. "bad"))))
    (is (thrown-with-msg? IllegalStateException #"state-\d+" (throw (IllegalStateException. "bad state-42"))))
    (are [x y] (= x y)
      2 (+ 1 1)
      9 (* 3 3)
      6 (+ 1 2 3)))

  (testing "condp with :>> ternary clauses and default"
    (is (= "123" (condp re-find "abc123def" #"(\d+)" :>> second :none)))
    (is (= 11 (condp #(%2 %1) {:a 10} :b :five :a :>> inc :other)))
    (is (= :other (condp #(%2 %1) {:a 10} :b :five :c :>> inc :other)))
    (is (thrown-with-msg? IllegalArgumentException #"No matching clause"
          (condp = 99 1 :one 2 :two))))

  (testing "Protocol per-method hierarchy fallback, caching, and invalidation"
    (extend-type CharSequence
      P4Proto
      (p4-a [cs] (str "cs:" (.length cs))))
    (extend-type String
      P4Proto
      (p4-b [s] (str "str:" s)))
    (is (satisfies? P4Proto "hi"))
    (is (extends? P4Proto String))
    (dotimes [_ 5]
      (is (= "cs:2" (p4-a "hi")))
      (is (= "str:hi" (p4-b "hi"))))
    (extend-type String
      P4Proto
      (p4-a [s] (str "override:" s)))
    (is (= "override:hi" (p4-a "hi")))
    (is (= "str:hi" (p4-b "hi"))))

  (testing "ns metadata, :refer-clojure, prefix require with :rename, and loaded-libs"
    (is (contains? (loaded-libs) 'clojure.string))
    (try
      (let [res (load-string
                  "(ns cljl.p4-ns \"p4 doc\" {:author \"cljl\"}
                     (:refer-clojure :exclude [get])
                     (:require (clojure [string :refer [join] :rename {join my-join}])))
                   (defn get [x] [:custom x])
                   [(:doc (meta (the-ns 'cljl.p4-ns)))
                    (:author (meta (the-ns 'cljl.p4-ns)))
                    (get 7)
                    (my-join \"-\" [\"a\" \"b\"])]")]
        (is (= ["p4 doc" "cljl" [:custom 7] "a-b"] res)))
      (finally
        (in-ns 'cljl.core-test))))

  (testing "clojure.string/replace-first with Fn and Character"
    (is (= "a24b34" (str/replace-first "a12b34" #"\d+" #(str (* 2 (parse-long %))))))
    (is (= "a:b-c" (str/replace-first "a-b-c" \- \:))))

  (testing "deftype mutable fields with set!"
    (let [c (->P4MutCell 0)]
      (is (= 0 (.getVal c)))
      (is (= 1 (.incAndGet c)))
      (is (= 2 (.incAndGet c)))
      (is (= 2 (.getVal c))))))

(defn ti-fib [n]
  (if (< n 2)
    n
    (+ (ti-fib (- n 1)) (ti-fib (- n 2)))))

(defn ti-ack [m n]
  (cond
    (= m 0) (inc n)
    (= n 0) (ti-ack (dec m) 1)
    :else (ti-ack (dec m) (ti-ack m (dec n)))))

(defn ti-mixed-arith [x]
  (+ x 1))

(defn ti-hinted-fn ^long [^long x ^double y]
  (+ x (long y)))

(defn ti-hypot [x y]
  (Math/sqrt (+ (* x x) (* y y))))

(defn ti-loop-recur [n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (+ acc i))
      acc)))

(defn ti-double-loop [n]
  (loop [i 0 acc 0.0]
    (if (< i n)
      (recur (inc i) (+ acc (double i)))
      acc)))

(deftest type-inference-and-specialization-test
  (testing "Recursive numeric specialization & heterogeneous fallback"
    (is (seq (cljl.TypeAnalyzer/discoverPrimSpecs
               (aget (.getFixedMethods ^cljl.Compiler$CompiledFn ti-fib) 1)
               ^cljl.Compiler$CompiledFn ti-fib)))
    (is (= 6765 (ti-fib 20)))
    (is (= 5.0 (ti-fib 5.0)))
    (is (= 55N (ti-fib 10N)))
    (is (= 3/2 (ti-fib 3/2)))
    (is (= 125 (ti-ack 3 4)))
    (is (= 11 (ti-mixed-arith 10)))
    (is (= 11.5 (ti-mixed-arith 10.5)))
    (is (= 10000000000000000000001N (ti-mixed-arith 10000000000000000000000N)))
    (is (= 3/2 (ti-mixed-arith 1/2))))

  (testing "Explicit type hints and direct Java interop"
    (is (= 13 (ti-hinted-fn 10 3.9)))
    (is (= 5.0 (ti-hypot 3 4)))
    (is (= 5.0 (ti-hypot 3.0 4.0)))
    (let [sb-fn (fn [x]
                  (let [^String s (str "hi-" x)
                        sb (StringBuilder. s)]
                    (.append sb "!")
                    [(.length s) (.toString sb)]))
          str-upper (fn [x] (.toUpperCase ^String x))]
      (is (true? (cljl.BytecodeJIT/canCompile sb-fn)))
      (is (some? (cljl.BytecodeJIT/compile sb-fn)))
      (is (= [5 "hi-42!"] (sb-fn 42)))
      (is (= "ABC" (str-upper "abc")))))

  (testing "Unboxed loop/recur, unchecked arithmetic, bit ops, and even?/odd?"
    (is (= 499500 (ti-loop-recur 1000)))
    (is (= 45.0 (ti-double-loop 10)))
    (let [f-bits (fn [x]
                   (let [a (bit-shift-left x 3)
                         b (bit-or a 5)
                         c (bit-and b 255)
                         d (bit-xor c 15)]
                     [(bit-shift-right d 1) (even? d) (odd? d) (bit-not 0)]))]
      (is (some? (cljl.BytecodeJIT/compile f-bits)))
      (is (= [1 true false -1] (f-bits 1)))))

  (testing "Exact overflow vs unchecked wrapping"
    (let [f-checked (fn [x] (+ x 1))
          f-inc (fn [x] (inc x))
          f-unchecked (fn [x] (unchecked-add x 1))
          f-uinc (fn [x] (unchecked-inc x))]
      (is (thrown? ArithmeticException (f-checked Long/MAX_VALUE)))
      (is (thrown? ArithmeticException (f-inc Long/MAX_VALUE)))
      (is (= Long/MIN_VALUE (f-unchecked Long/MAX_VALUE)))
      (is (= Long/MIN_VALUE (f-uinc Long/MAX_VALUE)))))

  (testing "NaN comparisons and equality semantics"
    (let [nan (/ 0.0 0.0)
          f-cmp (fn [a b] [(< a b) (<= a b) (> a b) (>= a b) (== a b) (= a b)
                           (zero? a) (pos? a) (neg? a)])]
      (is (= [false false false false false false false false false] (f-cmp nan 1.0)))
      (is (= [false false false false false false false true false] (f-cmp 1.0 nan)))
      (is (= [false false false false false true false false false] (f-cmp nan nan)))
      (is (false? (= (+ nan 0.0) (+ nan 0.0))))
      (is (false? (== (+ nan 0.0) (+ nan 0.0))))
      (is (= [false true false true true false false true false] (f-cmp 1 1.0)))))

  (testing "symbolic float printing (##Inf, ##-Inf, ##NaN)"
    (let [pinf (/ 1.0 0.0)
          ninf (/ -1.0 0.0)
          nan (/ 0.0 0.0)]
      (is (= "##Inf" (pr-str pinf)))
      (is (= "##Inf" (pr-str ##Inf)))
      (is (= "##Inf" (print-str pinf)))
      (is (= "##-Inf" (pr-str ninf)))
      (is (= "##-Inf" (print-str ninf)))
      (is (= "##NaN" (pr-str nan)))
      (is (= "##NaN" (print-str nan)))
      (is (= "##Inf" (pr-str (Float/valueOf Float/POSITIVE_INFINITY))))
      (is (= "##-Inf" (pr-str (Float/valueOf Float/NEGATIVE_INFINITY))))
      (is (= "##NaN" (pr-str (Float/valueOf Float/NaN))))
      (is (= "Infinity" (str pinf)))
      (is (= "-Infinity" (str ninf)))
      (is (= "NaN" (str nan)))
      (is (= ##Inf (read-string (pr-str pinf))))
      (is (= ##-Inf (read-string (pr-str ninf))))
      (is (Double/isNaN (read-string (pr-str nan))))))

  (testing "nil safety on type-hinted refs and collection intrinsics"
    (let [f-cnt (fn [^String s] (count s))
          f-len (fn [^String s] (.length s))
          f-first (fn [xs] (first xs))]
      (is (= 3 (f-cnt "abc")))
      (is (= 0 (f-cnt nil)))
      (is (= 3 (f-len "abc")))
      (is (thrown? NullPointerException (f-len nil)))
      (is (nil? (f-first nil)))
      (is (= 1 (f-first [1 2 3])))))

  (testing "with-redefs on self-recursive specialized Var calls"
    (is (= 21 (ti-fib 8)))
    (with-redefs [ti-fib (fn [n] (+ n 100))]
      (is (= 108 (ti-fib 8))))
    (let [orig ti-fib
          f-redef-rec (fn [n]
                        (if (< n 2) n (ti-fib (- n 1))))]
      (with-redefs [ti-fib (fn [n] (* n 10))]
        (is (= 40 (f-redef-rec 5))))
      (is (= orig ti-fib)))
    (is (= 21 (ti-fib 8)))))

(def untyped-vec-fixture (vec (range 128)))
(def untyped-map-fixture (zipmap (range 64) (range 64)))

(deftest collection-jit-and-fast-paths-test
  ;; Regression test for BytecodeJIT RT.assoc descriptor (previously threw NoSuchMethodError)
  (let [update-vec (fn [v n]
                     (loop [i 0 acc v]
                       (if (< i n)
                         (recur (inc i) (assoc acc i (* i 2)))
                         acc)))
        update-map (fn [m n]
                     (loop [i 0 acc m]
                       (if (< i n)
                         (recur (inc i) (assoc acc i (+ i 100)))
                         acc)))
        dissoc-map (fn [m n]
                     (loop [i 0 acc m]
                       (if (< i n)
                         (recur (inc i) (dissoc acc i))
                         acc)))
        conj-list (fn [n]
                    (loop [i 0 xs '()]
                      (if (< i n)
                        (recur (inc i) (conj xs i))
                        xs)))]
    ;; Warm up enough iterations to trigger Tier-1 BytecodeJIT compilation
    (dotimes [_ 200]
      (let [v2 (update-vec untyped-vec-fixture 64)]
        (is (= 126 (nth v2 63))))
      (let [m2 (update-map untyped-map-fixture 32)]
        (is (= 131 (get m2 31))))
      (let [m3 (dissoc-map untyped-map-fixture 32)]
        (is (= 32 (count m3)))
        (is (nil? (get m3 0)))))
    (let [xs (conj-list 100)]
      (is (list? xs))
      (is (counted? xs))
      (is (= 100 (count xs)))
      (is (= 99 (first xs)))
      (is (= 0 (nth xs 99)))
      (is (= :nf (nth xs 100 :nf)))
      (is (= 4950 (reduce + xs)))
      (is (= 4950 (reduce + 0 xs)))
      (is (= (range 100) (reverse xs))))
    (let [v (vec (range 100))
          sv (subvec v 10 90)]
      (is (= 4950 (reduce + v)))
      (is (= 3960 (reduce + sv)))
      (is (= (mapv inc (range 100)) (mapv inc v)))
      (is (= (filterv even? (range 100)) (filterv even? v)))
      (is (= (mapv inc (range 10 90)) (mapv inc sv)))
      (is (= (filterv odd? (range 10 90)) (filterv odd? sv)))
      (is (= (reverse (range 100)) (reverse v)))
      (is (= {0 1 2 3} (into {} [[0 1] [2 3]]))))
    ;; Phase 2 JIT/core fast-path coverage: pop, SubVec nth, set conj/get, list first/next/rest/seq, reduce + edge cases
    (let [pop-vec (fn [n]
                    (loop [v (vec (range n)) acc 0]
                      (if (pos? (count v))
                        (recur (pop v) (+ acc (peek v)))
                        acc)))
          sum-list-loop (fn [xs]
                          (loop [s (seq xs) acc 0]
                            (if s
                              (recur (next s) (+ acc (first s)))
                              acc)))
          sum-subvec (fn [n]
                       (let [sv (subvec (vec (range n)) 10 (- n 10))]
                         (loop [i 0 acc 0]
                           (if (< i (count sv))
                             (recur (inc i) (+ acc (nth sv i)))
                             acc))))
          build-set (fn [n]
                      (loop [i 0 s #{}]
                        (if (< i n)
                          (recur (inc i) (conj s i))
                          (get s (dec n)))))]
      (dotimes [_ 200]
        (is (= 190 (pop-vec 20)))
        (is (= 190 (sum-list-loop (apply list (range 20)))))
        (is (= 190 (sum-list-loop (cons 0 (apply list (range 1 20))))))
        (is (= 390 (sum-subvec 40)))
        (is (= 19 (build-set 20))))
      (is (= 0 (reduce + [])))
      (is (= 0 (reduce + '())))
      (is (= "keep" (reduce + ["keep"])))
      (is (= 10.5 (reduce + [1 2 3.5 4])))
      (is (= 10.5 (reduce + (list 1 2 3.5 4)))))))

(defmacro capture-form-env
  ([x] `(list '~(first &form) ~(nil? &env) ~(contains? &env 'y) ~(:mytag (meta &form)) ~x))
  ([x y] `(list '~(first &form) ~(nil? &env) ~(contains? &env y) ~x '~y)))

(def top-level-macro-capture ^:mytag (capture-form-env 42))

(deftest compatibility-and-primitive-array-test
  (testing "defmacro &form and &env"
    (is (= ['capture-form-env true false true 42]
           top-level-macro-capture))
    (is (= ['capture-form-env true false true 42]
           ((fn [] ^:mytag (capture-form-env 42)))))
    (let [y 10]
      (is (= ['capture-form-env false true nil 42]
             (capture-form-env 42)))
      (is (= ['capture-form-env false true 1 'y]
             (capture-form-env 1 y)))))

  (testing "Numeric division compatibility"
    (let [divide (fn [x y] (/ x y))
          divide-d (fn [^double x ^double y] (/ x y))]
      (dotimes [_ 200]
        (is (= 0.5 (divide 1.0 2.0)))
        (is (thrown? ArithmeticException (divide 1.0 0.0)))
        (is (= ##Inf (divide-d 1.0 0.0))))
      (is (= ##Inf (/ 1.0 0.0)))
      (is (= ##Inf (let [x 1.0 y 0.0] (/ x y))))))

  (testing "Checked narrowing casts and unchecked-* coercions"
    (let [as-long (fn [x] (long x))
          as-ulong (fn [x] (unchecked-long x))
          as-byte (fn [x] (byte x))
          as-ubyte (fn [x] (unchecked-byte x))
          as-short (fn [x] (short x))
          as-ushort (fn [x] (unchecked-short x))
          as-int (fn [x] (int x))
          as-uint (fn [x] (unchecked-int x))]
      (dotimes [_ 200]
        (is (= 42 (as-long 42.9)))
        (is (= 0 (as-long ##NaN)))
        (is (= Long/MAX_VALUE (as-long Long/MAX_VALUE)))
        (is (= Long/MIN_VALUE (as-long Long/MIN_VALUE)))
        (is (thrown? IllegalArgumentException (as-long 1.0E100)))
        (is (thrown? IllegalArgumentException (as-long -1.0E100)))
        (is (thrown? IllegalArgumentException (as-long ##Inf)))
        (is (= Long/MAX_VALUE (as-ulong 1.0E100)))
        (is (thrown? IllegalArgumentException (as-byte 128)))
        (is (thrown? IllegalArgumentException (as-byte -129)))
        (is (= -128 (as-ubyte 128)))
        (is (= -24 (as-ubyte 1000)))
        (is (thrown? IllegalArgumentException (as-short 40000)))
        (is (= -25536 (as-ushort 40000)))
        (is (thrown? IllegalArgumentException (as-int 1.0E10)))
        (is (= Integer/MAX_VALUE (as-uint 1.0E10))))
      (is (= \a (unchecked-char 97)))
      (is (= 1.5 (double (unchecked-float 1.5))))
      (is (= 2.5 (unchecked-double 2.5)))))

  (testing "Specialized primitive and object array operations (alength/aget/aset)"
    (let [sum-doubles (fn [^doubles xs]
                        (loop [x (double 0) i 0]
                          (if (< i (alength xs))
                            (recur (+ x (aget xs i)) (inc i))
                            x)))
          sum-longs (fn [^longs xs]
                      (loop [x 0 i 0]
                        (if (< i (alength xs))
                          (recur (+ x (aget xs i)) (inc i))
                          x)))
          fill-and-sum-ints (fn [^ints xs]
                              (loop [i 0]
                                (when (< i (alength xs))
                                  (aset xs i (* 2 i))
                                  (recur (inc i))))
                              (loop [s 0 i 0]
                                (if (< i (alength xs))
                                  (recur (+ s (aget xs i)) (inc i))
                                  s)))
          sum-bytes (fn [^bytes xs]
                      (loop [s 0 i 0]
                        (if (< i (alength xs))
                          (recur (+ s (aget xs i)) (inc i))
                          s)))
          sum-shorts (fn [^shorts xs]
                       (loop [s 0 i 0]
                         (if (< i (alength xs))
                           (recur (+ s (aget xs i)) (inc i))
                           s)))
          sum-floats (fn [^floats xs]
                       (loop [s (double 0) i 0]
                         (if (< i (alength xs))
                           (recur (+ s (aget xs i)) (inc i))
                           s)))
          count-trues (fn [^booleans xs]
                        (loop [c 0 i 0]
                          (if (< i (alength xs))
                            (recur (if (aget xs i) (inc c) c) (inc i))
                            c)))
          concat-chars (fn [^chars xs]
                         (aset xs 0 \Z)
                         (aget xs 0))
          obj-roundtrip (fn [^objects xs v]
                          (aset xs 1 v)
                          (aget xs 1))
          da (double-array [1.5 2.5 3.0 4.0])
          la (long-array [10 20 30])
          ia (int-array 4)
          ba (byte-array [1 2 3 -1])
          sa (short-array [100 200])
          fa (float-array [1.5 2.5])
          za (boolean-array [true false true])
          ca (char-array [\a \b])
          oa (object-array [:a :b :c])
          grid (to-array-2d [[1 2] [3 4]])]
      (dotimes [_ 200]
        (is (= 11.0 (sum-doubles da)))
        (is (= 60 (sum-longs la)))
        (is (= 12 (fill-and-sum-ints ia)))
        (is (= 5 (sum-bytes ba)))
        (is (= 300 (sum-shorts sa)))
        (is (= 4.0 (sum-floats fa)))
        (is (= 2 (count-trues za)))
        (is (= \Z (concat-chars ca)))
        (is (= :updated (obj-roundtrip oa :updated)))
        (aset grid 1 0 99)
        (is (= 99 (aget grid 1 0)))))))

(def summary (run-tests))
(when (or (pos? (:fail summary)) (pos? (:error summary)))
  (System/exit 1))
