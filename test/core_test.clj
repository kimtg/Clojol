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
    ;; Fast-long Atom: concurrent increments, demotion, compare-and-set!, watches, validators, overflow
    (let [a (atom 0)]
      (dotimes [_ 100] (swap! a inc))
      (is (= 100 @a))
      (swap! a dec)
      (is (= 99 @a))
      (swap! a + 1001)
      (is (= 1100 @a))
      (is (true? (compare-and-set! a @a 1101)))
      (is (= 1101 @a))
      (is (false? (compare-and-set! a 999 1102)))
      (is (true? (compare-and-set! a @a :demoted)))
      (is (= :demoted @a))
      (reset! a 50)
      (swap! a inc)
      (is (= 51 @a)))
    (let [a (atom 0)
          seen (atom 0)]
      (add-watch a :w (fn [_ _ o n] (swap! seen + (- n o))))
      (set-validator! a #(>= % 0))
      (is (thrown? IllegalStateException (reset! a -1)))
      (swap! a inc)
      (swap! a + 4)
      (is (= 5 @a))
      (is (= 5 @seen)))
    (let [a (atom 0)
          futs (doall (for [_ (range 4)]
                        (future (dotimes [_ 2500] (swap! a inc)))))]
      (doseq [f futs] @f)
      (is (= 10000 @a)))
    (let [a (atom Long/MAX_VALUE)]
      (is (thrown? ArithmeticException (swap! a inc))))
    (let [v (volatile! 1)]
      (vswap! v * 7)
      (is (= 7 @v)))
    ;; Scalar-replaced local volatile in loop, try/catch, mixed types, and return value
    (let [f (fn [n]
              (let [v (volatile! 0)]
                (loop [i 0]
                  (if (< i n)
                    (do (vswap! v + i)
                        (recur (inc i)))
                    @v))))]
      (is (= 4950 (f 100))))
    (let [f (fn []
              (let [v (volatile! 0)]
                (try
                  (vreset! v 10)
                  (vswap! v + 5 7)
                  (throw (RuntimeException. "stop"))
                  (catch RuntimeException _
                    @v))))]
      (is (= 22 (f))))
    (let [f (fn []
              (let [v (volatile! 0)]
                (vswap! v inc)
                (vreset! v 2.5)
                (vreset! v :done)
                (vreset! v 99)
                @v))]
      (is (= 99 (f))))
    ;; Escaping volatile (closure, parameter, cross-thread, single-eval of vol expr, type transitions)
    (let [evals (atom 0)
          v (volatile! 0)
          get-v (fn [] (swap! evals inc) v)
          bump (fn [vol d] (vswap! vol + d))]
      (is (volatile? v))
      (is (= 3 (vswap! (get-v) + 1 2)))
      (is (= 1 @evals))
      (is (= 13 (bump v 10)))
      (is (= 13 @v))
      (let [fut (future (vswap! v + 7))]
        (is (= 20 @fut))
        (is (= 20 @v)))
      (is (= :switched (vreset! v :switched)))
      (is (= :switched @v))
      (is (= 42 (vreset! v 42)))
      (is (= 42 @v)))
    (let [vd (volatile! 1.5)
          add-d (fn [vol x] (vswap! vol + x))]
      (is (= 4.0 (add-d vd 2.5)))
      (is (= 4.0 @vd)))
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

(deftest jit-try-variadic-case-and-fixes-test
  (testing "try/catch/finally JIT compilation including primitive types"
    (let [safe-div (fn [a b]
                     (try
                       (quot a b)
                       (catch ArithmeticException _ -1)))
          with-fin (fn [x a]
                     (try
                       (if (= x 0)
                         (throw (IllegalStateException. "boom"))
                         (+ x 10))
                       (catch IllegalStateException _
                         (swap! a conj :caught)
                         99)
                       (finally
                         (swap! a conj :finally))))
          nested-try (fn [x]
                       (try
                         (try
                           (if (< x 0) (throw (IllegalArgumentException. "neg")) (* x 2))
                           (catch IllegalArgumentException _ -10))
                         (finally
                           (+ 1 2))))
          rethrow-fin (fn [a]
                        (try
                          (throw (RuntimeException. "err"))
                          (finally
                            (swap! a conj :cleaned))))]
      (dotimes [_ 30]
        (is (= 5 (safe-div 10 2)))
        (is (= -1 (safe-div 10 0)))
        (is (= 10 (nested-try 5)))
        (is (= -10 (nested-try -1))))
      (let [log (atom [])]
        (dotimes [_ 20]
          (reset! log [])
          (is (= 15 (with-fin 5 log)))
          (is (= [:finally] @log))
          (reset! log [])
          (is (= 99 (with-fin 0 log)))
          (is (= [:caught :finally] @log))
          (reset! log [])
          (is (= "err" (try (rethrow-fin log) (catch RuntimeException e (.getMessage e)))))
          (is (= [:cleaned] @log))))))

  (testing "variadic fast-path bridges for 0-4 args"
    (let [vfn (fn [a & more]
                (if more
                  [a (vec more)]
                  [a :none]))
          vsum (fn [acc & nums]
                 (loop [s acc xs nums]
                   (if xs
                     (recur (+ s (first xs)) (next xs))
                     s)))]
      (dotimes [_ 30]
        (is (= [1 :none] (vfn 1)))
        (is (= [1 [2]] (vfn 1 2)))
        (is (= [1 [2 3]] (vfn 1 2 3)))
        (is (= [1 [2 3 4]] (vfn 1 2 3 4)))
        (is (= 10 (vsum 10)))
        (is (= 15 (vsum 10 5)))
        (is (= 18 (vsum 10 5 3)))
        (is (= 20 (vsum 10 5 3 2))))))

  (testing "case direct tableswitch, keyword/symbol identity, and string lookup"
    (let [dense-int (fn [^long n]
                      (case n
                        0 100
                        1 200
                        3 400
                        -1))
          kw-case (fn [k]
                    (case k
                      :alpha 1
                      :beta 2
                      :gamma 3
                      0))
          str-case (fn [s]
                     (case s
                       "one" 1
                       "two" 2
                       0))]
      (dotimes [_ 30]
        (is (= 100 (dense-int 0)))
        (is (= 200 (dense-int 1)))
        (is (= -1 (dense-int 2)))
        (is (= 400 (dense-int 3)))
        (is (= -1 (dense-int 99)))
        (is (= 1 (kw-case :alpha)))
        (is (= 3 (kw-case :gamma)))
        (is (= 0 (kw-case :other)))
        (is (= 1 (str-case "one")))
        (is (= 0 (str-case "three"))))))

  (testing "lazy StringSeq and single-entry PersistentHashMap"
    (let [s (seq "hello")]
      (is (= \h (first s)))
      (is (= 5 (count s)))
      (is (= 4 (count (next s))))
      (is (= '(\h \e \l \l \o) s))
      (is (= {:only 1} (hash-map :only 1)))
      (is (thrown? IllegalArgumentException (cljl.lang.PersistentHashMap/create (object-array [:a]))))))

  (testing "Var watches ignore thread-local set!, Namespace intern preserves meta, mixed Agent send/send-off"
    (let [watch-calls (atom 0)
          ^:dynamic *dyn* 1]
      (def ^:dynamic *test-watch-var* 10)
      (add-watch #'*test-watch-var* :w (fn [_ _ _ _] (swap! watch-calls inc)))
      (binding [*test-watch-var* 20]
        (set! *test-watch-var* 30)
        (is (= 30 *test-watch-var*)))
      (is (= 0 @watch-calls))
      (alter-var-root #'*test-watch-var* inc)
      (is (= 1 @watch-calls))
      (remove-watch #'*test-watch-var* :w))
    (eval '(def ^{:doc "keep-me"} meta-preserve-var 1))
    (eval '(def ^{:added "0.2"} meta-preserve-var 2))
    (is (= "keep-me" (:doc (meta (resolve 'meta-preserve-var)))))
    (is (= "0.2" (:added (meta (resolve 'meta-preserve-var)))))
    (let [ag (agent [])]
      (send ag conj :a)
      (send-off ag conj :b)
      (send ag conj :c)
      (is (await-for 5000 ag))
      (is (= [:a :b :c] @ag)))))

(defrecord CustomPrintRec [x])
(defstruct person-struct :name :age)
(defprotocol PTest (pmeth [this]))

(deftest clojure-core-parity-test
  (testing "all 92 parity symbols exist in clojure.core"
    (let [pubs (ns-publics 'clojure.core)
          expected '[*allow-unresolved-vars* *assert* *compiler-options* *flush-on-newline*
                     *fn-loader* *math-context* *print-namespace-maps* *read-eval*
                     *reader-resolver* *repl* *source-path* *suppress-read*
                     *use-context-classloader* *verbose-defrecords*
                     ->ArrayChunk ->Eduction ->Vec ->VecNode ->VecSeq
                     -cache-protocol-fn -reset-methods EMPTY-NODE Inst PrintWriter-on
                     accessor add-classpath agent-errors await1
                     chunk chunk-append chunk-buffer chunk-cons chunk-first chunk-next chunk-rest chunked-seq?
                     clear-agent-errors create-struct defmacro defstruct denominator destructure
                     find-protocol-impl find-protocol-method
                     hash-combine hash-ordered-coll hash-unordered-coll
                     inst-ms inst-ms* method-sig mix-collection-hash munge namespace-munge numerator
                     partitionv partitionv-all primitives-classnames
                     print-ctor print-dup print-method print-simple
                     proxy-call-with-super proxy-name
                     read+string reader-conditional reader-conditional? refer-clojure replicate
                     seq-to-map-for-destructuring special-symbol? splitv-at
                     stream-into! stream-reduce! stream-seq! stream-transduce!
                     struct struct-map tagged-literal tagged-literal?
                     unchecked-add-int unchecked-dec-int unchecked-divide-int unchecked-inc-int
                     unchecked-multiply-int unchecked-negate-int unchecked-remainder-int unchecked-subtract-int
                     unquote unquote-splicing with-bindings with-bindings* with-loading-context]]
      (doseq [sym expected]
        (is (contains? pubs sym) (str "Missing from clojure.core: " sym)))))

  (testing "Clojure 1.12 partitionv, partitionv-all, splitv-at, and stream-* ops"
    (is (= [[1 2] [3 4]] (partitionv 2 [1 2 3 4 5])))
    (is (= [[1 2] [3 4] [5 0]] (partitionv 2 2 [0] [1 2 3 4 5])))
    (is (vector? (first (partitionv 2 [1 2 3 4]))))
    (is (= [[1 2] [3 4] [5]] (partitionv-all 2 [1 2 3 4 5])))
    (is (= [[1 2] [3 4] [5]] (into [] (partitionv-all 2) [1 2 3 4 5])))
    (is (= [[1 2] '(3 4 5)] (splitv-at 2 [1 2 3 4 5])))
    (is (vector? (first (splitv-at 2 '(1 2 3 4)))))
    (is (= '(1 2 3) (stream-seq! (.stream [1 2 3]))))
    (is (nil? (stream-seq! (.stream []))))
    (is (= 10 (stream-reduce! + (.stream [1 2 3 4]))))
    (is (= 100 (stream-reduce! + 90 (.stream [1 2 3 4]))))
    (is (= 12 (stream-transduce! (map #(* 2 %)) + (.stream [1 2 3]))))
    (is (= [2 4 6] (stream-into! [] (map #(* 2 %)) (.stream [1 2 3]))))
    (is (= [1 2 3] (stream-into! [] (.stream [1 2 3])))))

  (testing "ratios, unchecked-*-int, Inst/inst-ms, hashing, munge, replicate"
    (is (= 3 (numerator 3/2)))
    (is (= 2 (denominator 3/2)))
    (is (= Integer/MIN_VALUE (unchecked-add-int Integer/MAX_VALUE 1)))
    (is (= Integer/MAX_VALUE (unchecked-subtract-int Integer/MIN_VALUE 1)))
    (is (= -2 (unchecked-multiply-int Integer/MAX_VALUE 2)))
    (is (= Integer/MIN_VALUE (unchecked-inc-int Integer/MAX_VALUE)))
    (is (= Integer/MAX_VALUE (unchecked-dec-int Integer/MIN_VALUE)))
    (is (= Integer/MIN_VALUE (unchecked-negate-int Integer/MIN_VALUE)))
    (is (= 3 (unchecked-divide-int 10 3)))
    (is (= 1 (unchecked-remainder-int 10 3)))
    (is (= 12345 (inst-ms (java.util.Date. 12345))))
    (is (= 12345 (inst-ms* (java.time.Instant/ofEpochMilli 12345))))
    (is (integer? (hash-combine 1 2)))
    (is (integer? (mix-collection-hash 42 3)))
    (is (= (hash-ordered-coll [1 2 3]) (hash-ordered-coll (java.util.List/of 1 2 3))))
    (is (= (hash-unordered-coll #{1 2}) (hash-unordered-coll (java.util.Set/of 2 1))))
    (is (= "foo_bar_QMARK__BANG_" (munge "foo-bar?!")))
    (is (= 'foo_bar_STAR_ (munge 'foo-bar*)))
    (is (= "my_app.core_util" (namespace-munge 'my-app.core-util)))
    (is (= '(:x :x :x) (replicate 3 :x))))

  (testing "defmacro, special-symbol?, destructure, with-bindings, tagged-literal, reader-conditional, read+string"
    (is (false? (special-symbol? 'defmacro)))
    (is (true? (special-symbol? 'if)))
    (is (true? (special-symbol? 'recur)))
    (is (vector? (destructure '[[a b] [1 2]])))
    (is (= {:a 1 :b 2} (seq-to-map-for-destructuring '(:a 1 :b 2))))
    (def ^:dynamic *wb-test* 1)
    (is (= 42 (with-bindings {#'*wb-test* 42} *wb-test*)))
    (is (= 43 (with-bindings* {#'*wb-test* 42} #(inc *wb-test*))))
    (is (= 1 *wb-test*))
    (is (= :ok (with-loading-context :ok)))
    (let [tl (tagged-literal 'foo [1 2])
          rc (reader-conditional '(:clj 1 :cljs 2) false)]
      (is (tagged-literal? tl))
      (is (= 'foo (:tag tl)))
      (is (= [1 2] (:form tl)))
      (is (= "#foo [1 2]" (pr-str tl)))
      (is (reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (:form rc)))
      (is (= false (:splicing? rc)))
      (let [preserved (read-string {:read-cond :preserve} "#?@(:clj [1 2])")]
        (is (reader-conditional? preserved))
        (is (= true (:splicing? preserved))))
      (let [[form raw] (read+string (java.io.StringReader. "  {:a 1}  rest"))]
        (is (= {:a 1} form))
        (is (= "{:a 1}" raw))))
    (is (thrown? IllegalStateException (unquote 1)))
    (is (thrown? IllegalStateException (unquote-splicing [1]))))

  (testing "structs, agent helpers, chunked seqs, ->Eduction, printing, protocols & proxy helpers"
    (let [s1 (struct person-struct "Alice" 30)
          s2 (struct-map person-struct :age 25 :name "Bob" :city "NYC")
          get-name (accessor person-struct :name)]
      (is (= "Alice" (get-name s1)))
      (is (= 30 (:age s1)))
      (is (= "Bob" (get-name s2)))
      (is (= "NYC" (:city s2)))
      (is (thrown? IllegalArgumentException (dissoc s1 :name)))
      (is (= {:name "Bob" :age 25} (dissoc s2 :city))))
    (let [ag (agent 10)]
      (send ag (fn [_] (throw (RuntimeException. "boom"))))
      (await1 ag)
      (is (= 1 (count (agent-errors ag))))
      (clear-agent-errors ag)
      (is (nil? (agent-errors ag)))
      (is (= 10 @ag)))
    (let [cb (chunk-buffer 4)]
      (chunk-append cb 10)
      (chunk-append cb 20)
      (let [ch (chunk cb)
            cs (chunk-cons ch '(30))]
        (is (chunked-seq? cs))
        (is (= 2 (count (chunk-first cs))))
        (is (= '(30) (chunk-next cs)))
        (is (= '(10 20 30) cs)))
      (is (= 2 (count (->ArrayChunk nil (object-array [:a :b :c]) 1 3)))))
    (let [vn (->VecNode nil (object-array [10 20 30]))
          v (->Vec nil 3 5 vn (.arr vn) nil)
          vs (->VecSeq nil v vn 0 0 nil)]
      (is (= [10 20 30] (vec v)))
      (is (= '(10 20 30) vs)))
    (is (= [2 4 6] (vec (->Eduction (map #(* 2 %)) [1 2 3]))))
    (is (some? EMPTY-NODE))
    (is (= "Long" (get primitives-classnames Long/TYPE)))
    (let [flushed (atom 0)
          closed (atom 0)
          pw (PrintWriter-on #(swap! flushed inc) #(swap! closed inc))]
      (.flush pw)
      (.close pw)
      (is (= 1 @flushed))
      (is (= 1 @closed)))
    (defmethod print-method CustomPrintRec [r w]
      (.write ^java.io.Writer w (str "#Custom[" (:x r) "]")))
    (is (= "#Custom[99]" (pr-str (->CustomPrintRec 99))))
    (extend-type String PTest (pmeth [s] (.length s)))
    (is (some? (find-protocol-impl PTest "abc")))
    (is (fn? (find-protocol-method PTest :pmeth "abc")))
    (is (= PTest (-reset-methods PTest)))
    (is (string? (proxy-name Object [java.lang.Runnable])))
    (let [^Class c String]
      (is (vector? (method-sig (first (.getMethods c))))))
    (binding [*assert* false]
      (is (nil? (eval '(assert false "should not fire")))))))

(deftest deserialization-security-test
  (let [serialize (fn [obj]
                    (let [baos (java.io.ByteArrayOutputStream.)
                          oos (java.io.ObjectOutputStream. baos)]
                      (.writeObject oos obj)
                      (.close oos)
                      (.toByteArray baos)))
        deserialize (fn [^bytes bs]
                      (let [ois (java.io.ObjectInputStream. (java.io.ByteArrayInputStream. bs))]
                        (.readObject ois)))
        deserialize-with (fn [^ClassLoader cl ^bytes bs]
                           (let [bais (java.io.ByteArrayInputStream. bs)
                                 ois (proxy [java.io.ObjectInputStream] [bais]
                                       (resolveClass [^java.io.ObjectStreamClass desc]
                                         (Class/forName (.getName desc) false cl)))]
                             (.readObject ois)))]
    (testing "Clojol functions reject Java serialization (GHSA-x4c4-68gp-vrhc)"
      (is (thrown? java.io.NotSerializableException (serialize (fn [x] x))))
      (is (thrown? java.io.NotSerializableException (serialize inc)))
      (is (thrown? java.io.NotSerializableException (serialize eval))))

    (testing "Generated proxy classes over Serializable superclasses reject Java serialization/deserialization by default"
      (let [p (proxy [javax.swing.table.AbstractTableModel] []
                (getRowCount [] 0)
                (getColumnCount [] 0)
                (getValueAt [r c] nil))
            e (proxy [java.lang.Exception] ["test"])
            cl (.getClassLoader (class e))]
        (is (thrown? java.io.NotSerializableException (serialize p)))
        (is (thrown? java.io.NotSerializableException (serialize e)))
        ;; Even if a proxy is serialized under -Dclojure.serializable.fns=true,
        ;; deserialization without the flag is blocked AND __mappings is transient.
        (let [bs (try
                   (System/setProperty "clojure.serializable.fns" "true")
                   (serialize e)
                   (finally
                     (System/clearProperty "clojure.serializable.fns")))]
          (is (thrown? java.io.NotSerializableException (deserialize-with cl bs)))
          (let [restored (try
                           (System/setProperty "clojure.serializable.fns" "true")
                           (deserialize-with cl bs)
                           (finally
                             (System/clearProperty "clojure.serializable.fns")))]
            (is (nil? (proxy-mappings restored)))))))

    (testing "Ratio Java serialization round-trip preserves invariants"
      (let [r 3/7
            r2 (deserialize (serialize r))]
        (is (= r r2))
        (is (= 3 (numerator r2)))
        (is (= 7 (denominator r2)))))

    (testing "clojure.edn rejects non-EDN reader syntax and ignores global *data-readers*"
      (require '[clojure.edn :as edn])
      (let [called (atom false)]
        (binding [*data-readers* {'evil (fn [x] (reset! called true) x)}
                  *default-data-reader-fn* (fn [t v] (reset! called true) [t v])]
          (is (thrown? RuntimeException (edn/read-string "#evil 1")))
          (is (false? @called))))
      (is (= [1 2 3] (edn/read-string {:readers {'vec3 (fn [x] (vec x))}} "#vec3 (1 2 3)")))
      (is (= [:custom 42] (edn/read-string {:default (fn [t v] [(keyword (name t)) v])} "#custom 42")))
      (is (thrown? RuntimeException (edn/read-string "#(+ 1 %)")))
      (is (thrown? RuntimeException (edn/read-string "#'clojure.core/inc")))
      (is (thrown? RuntimeException (edn/read-string "@foo")))
      (is (thrown? RuntimeException (edn/read-string "'foo")))
      (is (thrown? RuntimeException (edn/read-string "`foo")))
      (is (thrown? RuntimeException (edn/read-string "^:a {}")))
      (is (thrown? RuntimeException (edn/read-string "#?(:clj 1)")))
      (is (thrown? RuntimeException (edn/read-string "#=(+ 1 2)"))))))

(deftest volatile-loop-optimizations-test
  (testing "return-escaped volatile! (deferred materialization)"
    (let [make-counted (fn [n]
                         (let [v (volatile! 0)]
                           (loop [i 0]
                             (when (< i n)
                               (vswap! v inc)
                               (recur (inc i))))
                           v))
          v1 (make-counted 100)]
      (is (volatile? v1))
      (is (= 100 @v1))
      (is (= 101 (vswap! v1 inc))))
    (let [cond-ret (fn [flag]
                     (let [v (volatile! 10)]
                       (vswap! v + 5)
                       (if flag v (do (vreset! v :kw) v))))]
      (is (= 15 @(cond-ret true)))
      (is (= :kw @(cond-ret false))))
    (let [mixed-ret (fn [n]
                      (let [v (volatile! 0)]
                        (dotimes [_ n] (vswap! v inc))
                        (vreset! v (+ @v 0.5))
                        v))]
      (is (= 5.5 @(mixed-ret 5)))))

  (testing "loop-bound invariant vs rebound volatile!"
    (let [inv-fn (fn [n]
                   (loop [v (volatile! 0) i 0]
                     (if (< i n)
                       (do (vswap! v + i) (recur v (inc i)))
                       @v)))
          rebind-fn (fn [n]
                      (loop [v (volatile! 0) i 0]
                        (if (< i n)
                          (recur (volatile! (+ @v i)) (inc i))
                          @v)))]
      (is (= 4950 (inv-fn 100)))
      (is (= 4950 (rebind-fn 100)))))

  (testing "while range-bounded overflow check elimination and invalidation"
    (let [while-inc (fn [n]
                      (let [v (volatile! 0)]
                        (while (< @v n)
                          (vswap! v inc))
                        @v))
          while-dec (fn [n]
                      (let [v (volatile! n)]
                        (while (> @v 0)
                          (vswap! v dec))
                        @v))
          double-step-overflow (fn []
                                 (let [v (volatile! (- Long/MAX_VALUE 1))]
                                   (if (< @v Long/MAX_VALUE)
                                     (do
                                       (vswap! v inc)
                                       (vswap! v inc))
                                     @v)))]
      (is (= 1000 (while-inc 1000)))
      (is (= 0 (while-dec 1000)))
      ;; Second vswap! must NOT have its overflow check suppressed -> ArithmeticException
      (is (thrown? ArithmeticException (double-step-overflow)))))

  (testing "fused escaping Atom.Volatile ops (param, def, closure, overflow, mixed types)"
    (let [param-ops (fn [v n]
                      (dotimes [_ n] (vswap! v inc))
                      (dotimes [_ 2] (vswap! v dec))
                      (dotimes [_ 3] (vswap! v unchecked-inc))
                      (dotimes [_ 1] (vswap! v unchecked-dec))
                      (vswap! v + 10)
                      (vswap! v - 4)
                      (vswap! v * 2)
                      @v)
          v (volatile! 0)]
      (is (= 24 (param-ops v 6)))
      (is (= 24 @v)))

    ;; Overflow & 63-bit fast-long boundary transitions
    (let [v-max (volatile! Long/MAX_VALUE)
          v-min (volatile! Long/MIN_VALUE)
          v-62 (volatile! (dec (bit-shift-left 1 62)))
          v-uinc (volatile! Long/MAX_VALUE)
          inc-param (fn [vol] (vswap! vol inc))
          dec-param (fn [vol] (vswap! vol dec))
          uinc-param (fn [vol] (vswap! vol unchecked-inc))
          add-param (fn [vol x] (vswap! vol + x))
          mul-param (fn [vol x] (vswap! vol * x))]
      (is (thrown? ArithmeticException (inc-param v-max)))
      (is (thrown? ArithmeticException (dec-param v-min)))
      (is (= (bit-shift-left 1 62) (inc-param v-62)))
      (is (= (+ (bit-shift-left 1 62) 10) (add-param v-62 10)))
      (is (= Long/MIN_VALUE (uinc-param v-uinc)))
      (let [v-dbl (volatile! 1.5)]
        (is (= 4.0 (add-param v-dbl 2.5)))
        (is (= 8.0 (mul-param v-dbl 2.0)))
        (is (= 10.0 (add-param v-dbl 2))))
      (let [v-mix (volatile! 0)
            closure-add (fn [x] (vswap! v-mix + x))]
        (is (= 5 (closure-add 5)))
        (is (= 7.5 (closure-add 2.5)))
        (is (= :done (vreset! v-mix :done)))
        (is (= :done @v-mix))))

    ;; Multi-threaded visibility (acquire/release happens-before publication)
    (let [v (volatile! 0)
          done (volatile! false)
          writer (future
                   (dotimes [_ 10000] (vswap! v inc))
                   (vreset! done true))]
      @writer
      (is (true? @done))
      (is (= 10000 @v)))))

(defn ^:private rec-fib-test [n]
  (if (< n 2)
    n
    (+ (rec-fib-test (- n 1)) (rec-fib-test (- n 2)))))

(defn ^:private rec-fib-lte-test [n]
  (if (<= n 1)
    n
    (+ (rec-fib-lte-test (dec n)) (rec-fib-lte-test (- n 2)))))

(defn ^:private rec-fib-gte-test [n]
  (if (>= n 2)
    (+ (rec-fib-gte-test (- n 1)) (rec-fib-gte-test (- n 2)))
    n))

(defn ^:private rec-padovan-test [n]
  (if (< n 3)
    1
    (+ (rec-padovan-test (- n 2)) (rec-padovan-test (- n 3)))))

(defn ^:private rec-pell-test [n]
  (if (< n 2)
    n
    (+ (* 2 (rec-pell-test (- n 1))) (rec-pell-test (- n 2)))))

(defn ^:private rec-sub-const-test [n]
  (if (< n 2)
    n
    (+ (- (rec-sub-const-test (- n 1)) (rec-sub-const-test (- n 2))) 5)))

(defn ^:private rec-double-same-test [n]
  (if (< n 1)
    1
    (+ (rec-double-same-test (- n 1)) (rec-double-same-test (- n 1)))))

(defn ^:private rec-tak-test [x y z]
  (if (<= x y)
    z
    (rec-tak-test (rec-tak-test (- x 1) y z)
                  (rec-tak-test (- y 1) z x)
                  (rec-tak-test (- z 1) x y))))

(declare rec-var-fib-test)
(def ^:private rec-var-fib-test
  (fn [n]
    (if (< n 2)
      n
      (+ (rec-var-fib-test (- n 1)) (rec-var-fib-test (- n 2))))))

(deftest pure-self-recursion-optimization-test
  (testing "pure self-recursive static worker, linear-decrement range unrolling, and depth-2 inlining preserve exact semantics"
    (is (= 0 (rec-fib-test 0)))
    (is (= 1 (rec-fib-test 1)))
    (is (= 1 (rec-fib-test 2)))
    (is (= 2 (rec-fib-test 3)))
    (is (= 3 (rec-fib-test 4)))
    (is (= 5 (rec-fib-test 5)))
    (is (= 8 (rec-fib-test 6)))
    (is (= 13 (rec-fib-test 7)))
    (is (= 55 (rec-fib-test 10)))
    (is (= 6765 (rec-fib-test 20)))
    (is (= -5 (rec-fib-test -5)))
    (is (= Long/MIN_VALUE (rec-fib-test Long/MIN_VALUE)))
    ;; Alternative guard orientations (<=, >=) and dec
    (doseq [k [-3 0 1 2 3 4 5 6 7 10 20]]
      (is (= (rec-fib-test k) (rec-fib-lte-test k)))
      (is (= (rec-fib-test k) (rec-fib-gte-test k))))
    ;; Padovan (B=3, cMin=2, cMax=3) and weighted Pell (K=3)
    (is (= [1 1 1 2 2 3 4 5 7 9 12 16 21 28 37 49]
           (mapv rec-padovan-test (range 16))))
    (is (= [0 1 2 5 12 29 70 169 408 985 2378]
           (mapv rec-pell-test (range 11))))
    ;; Canceling subtraction with constant addend: f(n) = f(n-1) - f(n-2) + 5
    ;; 0, 1, 6, 10, 9, 4, 0, 1, 6, 10, 9, 4
    (is (= [0 1 6 10 9 4 0 1 6 10 9 4]
           (mapv rec-sub-const-test (range 12))))
    ;; Duplicate-offset CSE: f(n) = f(n-1) + f(n-1) = 2^n
    (is (= [1 2 4 8 16 32 64 128 256 512 1024]
           (mapv rec-double-same-test (range 11))))
    ;; Exact fib(40), fib(77), fib(92), and fib(93) overflow
    (is (= 102334155 (rec-fib-test 40)))
    (is (= 5527939700884757 (rec-fib-test 77)))
    (is (= 5527939700884757 (rec-fib-test 77)))
    (is (= 7540113804746346429 (rec-fib-test 92)))
    (is (thrown? ArithmeticException (rec-fib-test 93)))
    ;; Exceptions must never be cached
    (is (thrown? ArithmeticException (rec-fib-test 93)))
    (is (= 7 (rec-tak-test 18 12 6)))
    (is (= 9 (rec-tak-test 24 16 8)))
    (is (= 55 (rec-var-fib-test 10)))
    ;; General pure multi-branch auto-memoization (1-arg, case, BigInt +', 2-arg, 3-arg)
    (let [trib (fn trib [n]
                 (if (< n 3) n (+ (trib (- n 1)) (trib (- n 2)) (trib (- n 3)))))
          case-fib (fn cf [n]
                     (case n 0 0 1 1 (+ (cf (- n 1)) (cf (- n 2)))))
          fib-big (fn fb [n]
                    (if (< n 2) n (+' (fb (- n 1)) (fb (- n 2)))))
          choose (fn ch [n k]
                   (if (or (== k 0) (== k n))
                     1
                     (+ (ch (- n 1) (- k 1)) (ch (- n 1) k))))
          paths (fn gp [r c]
                  (if (or (== r 0) (== c 0))
                    1
                    (+ (gp (- r 1) c) (gp r (- c 1)))))
          min-ret (fn mr [n]
                    (if (< n 2)
                      Long/MIN_VALUE
                      (let [a (mr (- n 1)) b (mr (- n 2))] (if (== a b) a 0))))]
      (is (= 3927900252338210 (trib 60)))
      (is (= 5527939700884757 (case-fib 77)))
      (is (= 280571172992510140037611932413038677189525N (fib-big 200)))
      (is (= 5527939700884757N (fib-big 77N)))
      (is (= 118264581564861424 (choose 60 30)))
      (is (= 118264581564861424 (paths 30 30)))
      ;; Legitimate Long/MIN_VALUE return value (same as MISS sentinel) must remain exact
      (is (= Long/MIN_VALUE (min-ret 10))))
    ;; Impure recursive function MUST NOT be memoized (all 2*F(11)-1 = 177 calls execute)
    (let [cnt (volatile! 0)
          impure-fib (fn imp [n]
                       (vswap! cnt inc)
                       (if (< n 2) n (+ (imp (- n 1)) (imp (- n 2)))))]
      (is (= 55 (impure-fib 10)))
      (is (= 177 @cnt))
      (is (= 55 (impure-fib 10)))
      (is (= 354 @cnt)))
    ;; Concurrent auto-memoized calls must all return exact results
    (let [futs (mapv (fn [_] (future (rec-fib-test 77))) (range 8))]
      (is (every? #(= 5527939700884757 @%) futs)))
    ;; Overflow is still detected accurately in pure recursive functions (both small & large base)
    (let [f (fn ovf [n]
              (if (< n 1)
                Long/MAX_VALUE
                (+ 1 (ovf (- n 1)))))]
      (is (thrown? ArithmeticException (f 1))))
    (let [f (fn ovf2 [n]
              (if (< n 2)
                5000000000000000000
                (+ (ovf2 (- n 1)) (ovf2 (- n 2)))))]
      (is (thrown? ArithmeticException (f 2)))))
  (testing "with-redefs still intercepts Var-recursive defns"
    (let [orig rec-var-fib-test]
      (with-redefs [rec-var-fib-test (fn [n] (if (= n 2) 100 (orig n)))]
        ;; Calling orig directly while #'rec-var-fib-test is redefined must take the guarded slow path
        (is (= 101 (orig 3)))))))

(deftest atom-optimization-test
  (testing "local scalar-replaced atom preserves exact semantics"
    ;; Local while + inc
    (let [f (fn [n]
              (let [a (atom 0)]
                (while (< @a n) (swap! a inc))
                @a))]
      (is (= 10000 (f 10000)))
      (is (= 0 (f 0))))
    ;; Tail-escaping local atom materializes cleanly as an Atom
    (let [make-a (fn [n]
                   (let [a (atom 0)]
                     (dotimes [_ n] (swap! a inc))
                     a))
          a (make-a 500)]
      (is (instance? cljl.Atom a))
      (is (= 500 @a))
      (is (= 501 (swap! a inc))))
    ;; Loop-bound invariant atom + alias + reset! + arithmetic ops
    (let [f (fn [n]
              (loop [a (atom 10) i 0]
                (if (< i n)
                  (let [b a]
                    (swap! b + 2)
                    (swap! a - 1)
                    (recur a (inc i)))
                  @a)))]
      (is (= 110 (f 100))))
    ;; Local atom with collection swap! (conj)
    (let [f (fn [n]
              (let [a (atom [])]
                (dotimes [i n] (swap! a conj i))
                @a))]
      (is (= [0 1 2 3 4] (f 5))))
    ;; Exception inside swap! leaves local scalar atom unchanged
    (let [f (fn []
              (let [a (atom 42)]
                (try
                  (swap! a (fn [_] (throw (RuntimeException. "boom"))))
                  (catch RuntimeException _))
                @a))]
      (is (= 42 (f))))
    ;; Overflow detection on local scalar atom
    (let [f (fn []
              (let [a (atom Long/MAX_VALUE)]
                (swap! a inc)))]
      (is (thrown? ArithmeticException (f))))
    ;; with-redefs on inc still intercepts local scalar atom swap!
    (let [f (fn []
              (let [a (atom 0)]
                (swap! a inc)
                @a))]
      (is (= 1 (f)))
      (with-redefs [inc (fn [x] (+ x 10))]
        (is (= 10 (f))))))
  (testing "escaping atom fast paths: xadd, sub, mul, unchecked, swapLong, swap-vals!, reset-vals!, conj"
    (let [a (atom 10)]
      (is (= 11 (swap! a inc)))
      (is (= 10 (swap! a dec)))
      (is (= 11 (swap! a unchecked-inc)))
      (is (= 10 (swap! a unchecked-dec)))
      (is (= 15 (swap! a + 5)))
      (is (= 12 (swap! a - 3)))
      (is (= 36 (swap! a * 3)))
      (is (= 40 (swap! a unchecked-add 4)))
      (is (= 30 (swap! a unchecked-subtract 10)))
      (is (= 60 (swap! a unchecked-multiply 2)))
      (is (= [60 61] (swap-vals! a inc)))
      (is (= [61 59] (swap-vals! a - 2)))
      (is (= [59 100] (reset-vals! a 100)))
      (is (= 100 @a)))
    ;; Used primitive long return from escaping atom swap!
    (let [f (fn [a n]
              (loop [i 0 s 0]
                (if (< i n)
                  (recur (inc i) (+ s (long (swap! a inc))))
                  s)))
          a (atom 0)]
      (is (= 55 (f a 10)))
      (is (= 10 @a)))
    ;; Boundary crossing near 2^60 and Long/MAX_VALUE overflow
    (let [a (atom (bit-shift-left 1 60))]
      (is (= (+ (bit-shift-left 1 60) 1) (swap! a inc)))
      (is (= (+ (bit-shift-left 1 60) 2) (swap! a + 1))))
    (let [a (atom Long/MAX_VALUE)]
      (is (thrown? ArithmeticException (swap! a inc)))
      (is (= Long/MIN_VALUE (swap! a unchecked-inc))))
    ;; Escaping collection swap! (conj fast path)
    (let [a (atom [])]
      (dotimes [i 100] (swap! a conj i))
      (is (= 100 (count @a)))
      (is (= 99 (peek @a))))
    ;; Re-entrant swap! on the same thread must detect state change and retry
    (let [a (atom 0)
          res (swap! a (fn [v]
                         (when (= v 0) (reset! a 99))
                         (inc v)))]
      (is (= 100 res))
      (is (= 100 @a)))
    ;; Raw Java Thread interop must revoke single-thread bias before the child thread runs
    (let [a (atom 0)
          t (Thread. ^Runnable (fn [] (dotimes [_ 25000] (swap! a inc))))]
      (.start t)
      (dotimes [_ 25000] (swap! a inc))
      (.join t)
      (is (= 50000 @a))
      (is (false? (cljl.Atom/isSingleThreaded))))
    ;; Multi-threaded contended wait-free xadd + concurrent transition to boxed mode
    (let [a (atom 0)
          futs (mapv (fn [_]
                       (future
                         (dotimes [_ 50000] (swap! a inc))))
                     (range 4))]
      (run! deref futs)
      (is (= 200000 @a)))
    ;; Concurrent xadd + add-watch (forces ensureBoxed mid-flight) must not lose any updates
    (let [a (atom 0)
          seen (atom 0)
          futs (mapv (fn [tid]
                       (future
                         (dotimes [i 20000]
                           (when (and (= tid 0) (= i 5000))
                             (add-watch a :w (fn [_ _ _ _] (swap! seen inc))))
                           (swap! a inc))))
                     (range 4))]
      (run! deref futs)
      (is (= 80000 @a))
      (is (pos? @seen)))))

(defprotocol IPicTest (pic-op [this x]))
(defrecord PicRecA [v]
  IPicTest
  (pic-op [_ x] (+ v x)))
(defrecord PicRecB [v]
  IPicTest
  (pic-op [_ x] (* v x)))
(extend-type nil
  IPicTest
  (pic-op [_ x] (- x)))

(require '[clojure.core.reducers :as r])

(deftest phase1-to-5-improvements-test
  (testing "Phase 1: Protocol PIC dispatch works with defrecord, extend-type, nil, reify, and cache invalidation"
    (let [a (->PicRecA 10)
          b (->PicRecB 3)
          r1 (reify IPicTest (pic-op [_ x] (+ x 100)))
          r2 (reify IPicTest (pic-op [_ x] (+ x 200)))]
      (is (= 15 (pic-op a 5)))
      (is (= 15 (pic-op b 5)))
      ;; Exercise JIT-compiled defrecord direct getfield + Protocol.MethodFn invokePrim_OJ_J
      (is (= 1725 (loop [i 0 s 0] (if (< i 50) (recur (inc i) (+ s (long (pic-op a i)))) s))))
      (is (= -5 (pic-op nil 5)))
      ;; Two reify instances sharing Proxy class must not poison each other's dispatch
      (is (= 105 (pic-op r1 5)))
      (is (= 205 (pic-op r2 5)))
      ;; Cache invalidation on extend-type override
      (extend-type PicRecA
        IPicTest
        (pic-op [this x] (+ (:v this) x 1000)))
      (is (= 1015 (pic-op a 5)))))
  (testing "Phase 2: LongRange, IReduceInit, chunked map/filter/keep/take/drop, transduce, into"
    (let [r (range 1000)]
      (is (counted? r))
      (is (= 1000 (count r)))
      (is (chunked-seq? r))
      (is (= 499500 (reduce + 0 r)))
      (is (= [10 8 6 4 2] (vec (range 10 0 -2))))
      (is (= () (range 5 5)))
      (is (= () (range 5 2 1)))
      (is (= [0 1 2 3 4] (vec (take 5 (range))))))
    (is (= 25005000 (reduce + 0 (filter even? (map inc (range 10000))))))
    (is (= 25005000 (transduce (comp (map inc) (filter even?)) + 0 (range 10000))))
    (is (= 5000 (count (into [] (comp (map inc) (filter even?)) (range 10000)))))
    (is (= [2 4 6] (vec (take 3 (drop 1 (filter even? (range 20)))))))
    (is (= [1 3 5] (vec (keep #(when (odd? %) %) (range 6)))))
    (is (= "0,1,2,3,4" (clojure.string/join "," (range 5)))))
  (testing "Phase 3: Keyword lookup fast path, HAMT maps, and ArraySeq sort"
    (let [m {:a 1 :b 2 :c 3}]
      (is (= 1 (:a m)))
      (is (= 2 (:b m)))
      (is (= :nf (:missing m :nf))))
    (let [s (sort (rseq (vec (range 100))))]
      (is (seq? s))
      (is (counted? s))
      (is (= 100 (count s)))
      (is (= 0 (first s)))
      (is (= 99 (last s)))))
  (testing "Phase 4: Reader rejects nested #() and JIT stack traces include readable fn names"
    (is (thrown? RuntimeException (read-string "#(#(+ % 1) %)")))
    (let [boom-fn (fn my-jit-boom [n]
                    (loop [i 0]
                      (if (< i n) (recur (inc i)) (throw (IllegalStateException. "boom")))))]
      (dotimes [_ 3]
        (try (boom-fn 1000) (catch IllegalStateException _)))
      (let [trace (try
                    (boom-fn 1000)
                    ""
                    (catch IllegalStateException e
                      (apply str (map str (.getStackTrace e)))))]
        (is (.contains trace "my_jit_boom")))))
  (testing "Math constant folding and unboxed primitive Fn dispatch"
    (is (= 0.0 (Math/sin 0)))
    (is (= 1.0 (Math/cos 0)))
    (is (= 1.0 (StrictMath/cos 0)))
    (is (= 8.0 (Math/pow 2 3)))
    (is (= 5 (Math/abs -5)))
    (is (= 5.5 (Math/abs -5.5)))
    (let [f (fn ^double [^double x] (Math/sin x))
          g (fn ^long [^long x] (+ x 10))
          h (fn ^long [^long a ^long b] (+ a b))]
      (is (= 0.0 (loop [i 0 s 0.0] (if (< i 50) (recur (inc i) (+ s (f 0.0))) s))))
      (is (= 1725 (loop [i 0 s 0] (if (< i 50) (recur (inc i) (+ s (long (g i)))) s))))
      (is (= 2450 (loop [i 0 s 0] (if (< i 50) (recur (inc i) (+ s (long (h i i)))) s)))))
    ;; Verify non-long map transducer still works safely with LongRange
    (is (= ["0" "1" "2"] (into [] (map str) (range 3))))
    (is (= 90 (transduce (comp (map (fn [x] (* x 2))) (filter #(> % 0))) + 0 (range 1 10)))))
  (testing "FastSort radix/merge/stability & LongArraySeq semantics"
    ;; Radix sort with negative, MIN_VALUE, MAX_VALUE, duplicates (>256 elements)
    (let [xs (concat [Long/MAX_VALUE Long/MIN_VALUE -1 0 1 -999999999999 999999999999]
                     (map #(rem (* % 7919) 1000) (range 400)))
          s (sort xs)]
      (is (counted? s))
      (is (= 407 (count s)))
      (is (= Long/MIN_VALUE (first s)))
      (is (= Long/MAX_VALUE (last s)))
      (is (apply <= s))
      (is (= (reduce +' 0 xs) (reduce +' 0 s)))
      (is (= (vec s) (vec (sort s)))))
    ;; Stability of sort-by on equal keys (both <256 merge and >=256 radix)
    (let [items (mapv (fn [i] {:k (mod i 5) :id i}) (range 300))
          sorted (vec (sort-by :k items))]
      (is (= 300 (count sorted)))
      (is (every? (fn [k]
                    (let [ids (map :id (filter #(= k (:k %)) sorted))]
                      (apply < ids)))
                  (range 5))))
    ;; Mixed Long/Double/String/nil sorts still preserve RT.compare semantics
    (is (= [nil 1 2.0 3] (vec (sort [3 nil 2.0 1]))))
    (is (= ["a" "b" "c"] (vec (sort ["c" "a" "b"]))))
    (is (= [{:k "a" :i 1} {:k "a" :i 2} {:k "b" :i 0}]
           (vec (sort-by :k [{:k "b" :i 0} {:k "a" :i 1} {:k "a" :i 2}])))))
  (testing "Project Euler 1, mod intrinsic, LongArrayChunk, and clojure.core.reducers"
    (let [mult35? (fn [x] (or (zero? (rem x 3)) (zero? (rem x 5))))
          mult35-mod? (fn [x] (or (zero? (mod x 3)) (zero? (mod x 5))))]
      (is (= 2 (mod -1 3)))
      (is (= -2 (mod 1 -3)))
      (is (= -1 (mod -1 -3)))
      (is (= 0 (mod 0 3)))
      (is (= 233168 (reduce + (filter mult35? (range 1000)))))
      (is (= 233168 (reduce + 0 (filter mult35-mod? (range 1000)))))
      (is (= 233168 (transduce (filter mult35?) + 0 (range 1000))))
      (is (= 233168 (r/reduce + 0 (r/filter mult35? (range 1000)))))
      (is (= 233168 (r/fold + (r/filter mult35? (range 1000)))))
      (is (= 233168 (r/fold + (r/filter mult35? (vec (range 1000))))))
      ;; Larger parallel fold exceeding default n=512
      (is (= 23331668 (r/fold + (r/filter mult35? (range 10000)))))
      (is (= 23331668 (r/fold + (r/filter mult35? (vec (range 10000))))))
      (is (= 23331668 (r/fold 256 + + (r/filter mult35? (range 10000)))))
      ;; r/map, r/remove, r/take, r/drop, r/monoid, r/foldcat
      (is (= [2 4 6] (vec (r/foldcat (r/map #(* % 2) (r/filter pos? (range -2 4)))))))
      (is (= [1 3 5] (into [] (r/remove even? (range 6)))))
      (is (= 10 (r/reduce + 0 (r/take 5 (range 100)))))
      (is (= 24 (r/fold (r/monoid * (constantly 1)) (vec (range 1 5)))))
      ;; Lazy memoization preserved with LongArrayChunk
      (let [calls (atom 0)
            s (filter (fn [x] (swap! calls inc) (even? x)) (range 100))]
        (is (= 2450 (reduce + s)))
        (let [c1 @calls]
          (is (= 0 (first s)))
          (is (= 50 (count s)))
          (is (= c1 @calls)))))))

(require '[unloaded.alias.demo :as-alias uad]
         '[clojure.java.process :as proc]
         '[clojure.repl.deps :as rdeps]
         '[clojure.core.protocols :as cp]
         '[clojure.core.server :as srv]
         '[clojure.stacktrace :as st]
         '[clojure.template :as tmpl]
         '[clojure.instant :as inst]
         '[clojure.reflect :as refl]
         '[clojure.uuid]
         '[clojure.pprint :as pp])

(deftest clojure-1-12-replacement-test
  (testing "Phase 1: Method values, qualified calls, param-tags, array classes, SAM adaptation, :as-alias, process"
    (is (= [1 2 3] (mapv ^[String] Integer/parseInt ["1" "2" "3"])))
    (is (= 42 (^[String] Integer/parseInt "42")))
    (is (= ["A" "B"] (mapv String/.toUpperCase ["a" "b"])))
    (is (= ["hi" "yo"] (mapv str (mapv ^[String] StringBuilder/new ["hi" "yo"]))))
    (is (= 2 (String/.indexOf "hello" "l")))
    (is (= "llo" (^[int] String/.substring "hello" 2)))
    (is (= "el" (^[_ int] String/.substring "hello" 1 3)))
    (is (= (Class/forName "[Ljava.lang.String;") String/1))
    (is (= (Class/forName "[[J") long/2))
    (is (= [10 20 30]
           (let [xs (java.util.ArrayList.)]
             (.forEach (.stream [1 2 3]) #(.add xs (* % 10)))
             (vec xs))))
    (is (= [2 4] (vec (.toList (.filter (.map (.stream [1 2 3 4]) #(* % 1)) even?)))))
    (is (= 15 (.reduce (.stream [1 2 3 4 5]) 0 +)))
    (is (= :unloaded.alias.demo/foo ::uad/foo))
    (is (fn? proc/start))
    (is (fn? proc/exec))
    (is (fn? rdeps/add-lib))
    (is (fn? rdeps/add-libs))
    (is (fn? rdeps/sync-deps)))

  (testing "Phase 2: PersistentQueue, clojure.lang.* interfaces, ex-info maps, self-referencing deftype"
    (let [q (conj clojure.lang.PersistentQueue/EMPTY 1 2 3)]
      (is (= 1 (peek q)))
      (is (= [1 2 3] q))
      (is (= q [1 2 3]))
      (is (= q '(1 2 3)))
      (is (= (hash [1 2 3]) (hash q)))
      (is (= [2 3] (pop q)))
      (is (= 3 (count q)))
      (is (= clojure.lang.PersistentQueue/EMPTY (empty q)))
      (is (= clojure.lang.PersistentQueue/EMPTY (pop clojure.lang.PersistentQueue/EMPTY)))
      (is (nil? (peek clojure.lang.PersistentQueue/EMPTY)))
      (is (sequential? q))
      (is (not (seq? q)))
      (is (= 6 (reduce + 0 q)))
      (is (= "(1 2 3)" (pr-str q))))
    (is (not= [[:a 1]] (sorted-map :a 1)))
    (let [e (ex-info "boom" (sorted-map :code 500))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (instance? clojure.lang.IExceptionInfo e))
      (is (= {:code 500} (ex-data e))))
    (is (instance? clojure.lang.IPersistentMap {:a 1}))
    (is (instance? clojure.lang.IPersistentVector [1 2]))
    (is (instance? clojure.lang.Indexed [1 2]))
    (is (instance? clojure.lang.IPersistentSet #{1 2}))
    (is (instance? clojure.lang.Named :a/b))
    (is (instance? clojure.lang.Sequential [1 2]))
    (is (instance? clojure.lang.IPending (lazy-seq [1])))
    (eval '(do
             (deftype SelfRefBox [v]
               clojure.lang.Seqable
               clojure.lang.IPersistentCollection
               (seq [_] (seq [v]))
               Object
               (equals [_ other] (and (instance? SelfRefBox other) (= v (.-v other))))
               (toString [_] (str "Box(" v ")")))
             (assert (= (SelfRefBox. 7) (SelfRefBox. 7)))
             (assert (= "Box(7)" (str (SelfRefBox. 7)))))))

  (testing "Phase 3: core.protocols, server/prepl, stacktrace, template, instant, reflect, uuid, pprint"
    (is (= 10 (cp/coll-reduce [1 2 3 4] + 0)))
    (is (= 6 (cp/kv-reduce {:a 1 :b 2 :c 3} (fn [a _ v] (+ a v)) 0)))
    (is (= '(+ 1 2) (tmpl/apply-template '[x y] '(+ x y) [1 2])))
    (let [d (inst/read-instant-date "2026-01-02T03:04:05Z")]
      (is (instance? java.util.Date d))
      (is (.startsWith (pr-str d) "#inst \"2026-01-02T03:04:05")))
    (let [u (random-uuid)]
      (is (= u (read-string (pr-str u)))))
    (let [r (refl/reflect String)]
      (is (contains? (:flags r) :public))
      (is (some #(= 'length (:name %)) (:members r))))
    (let [root (IllegalArgumentException. "root")
          outer (RuntimeException. "outer" root)]
      (is (identical? root (st/root-cause outer))))
    (is (= "42" (pp/write 42 :stream nil :pretty false)))
    (is (= "x=5" (with-out-str ((pp/formatter-out "x=~a") 5))))
    (let [ss (srv/start-server {:name "core-test-prepl" :port 0 :accept 'clojure.core.server/io-prepl})
          port (.getLocalPort ss)]
      (try
        (let [out (atom [])]
          (srv/remote-prepl "127.0.0.1" port (java.io.StringReader. "(+ 20 22)\n") #(swap! out conj %))
          (is (= 1 (count @out)))
          (is (= :ret (:tag (first @out))))
          (is (= "42" (:val (first @out)))))
        (finally
          (srv/stop-server "core-test-prepl")))))

  (testing "Phase 4: clojure.java.api.Clojure, clojure.lang.IFn, gen-class"
    (let [plus (clojure.java.api.Clojure/var "clojure.core" "+")
          inc-fn (clojure.java.api.Clojure/var "clojure.core/inc")]
      (is (instance? clojure.lang.IFn plus))
      (is (= 15 (.invoke plus 1 2 3 4 5)))
      (is (= 42 (.invoke inc-fn 41)))
      (is (= {:k 1} (clojure.java.api.Clojure/read "{:k 1}"))))
    (eval '(do
             (ns cljl.genclass-test-demo
               (:gen-class
                 :name cljl.GenClassDemo
                 :state state
                 :init init
                 :constructors {[String] []}
                 :methods [[greet [String] String]
                           ^{:static true} [add [long long] long]]
                 :main true))
             (defn -init [prefix] [[] prefix])
             (defn -greet [this who] (str (.-state this) ", " who "!"))
             (defn -add [a b] (+ a b))
             (def main-args (atom nil))
             (defn -main [& args] (reset! main-args (vec args)))
             (let [g (cljl.GenClassDemo. "Hi")]
               (assert (= "Hi, World!" (.greet g "World")))
               (assert (= 7 (cljl.GenClassDemo/add 3 4)))
               (cljl.GenClassDemo/main (into-array String ["x" "y"]))
               (assert (= ["x" "y"] @main-args)))))
    (in-ns 'cljl.core-test))

  (testing "Phase 5 regressions: {:pre :post}, defonce in do, *unchecked-math*, *warn-on-reflection* in load"
    (let [f (fn [x] {:pre [(pos? x)] :post [(even? %)]} (* x 2))]
      (is (= 6 (f 3)))
      (is (thrown? AssertionError (f -1))))
    (let [g (fn [x] {:post [(pos? %)]} (- x 10))]
      (is (thrown? AssertionError (g 5))))
    (is (= :first
           (load-string "(do (defonce __defonce_test_val__ :first) (defonce __defonce_test_val__ :second) __defonce_test_val__)")))
    (is (= Long/MIN_VALUE
           (load-string "(set! *unchecked-math* true) (+ Long/MAX_VALUE 1)")))
    (is (= :ok
           (load-string "(set! *warn-on-reflection* true) :ok")))
    (is (thrown? java.io.FileNotFoundException (slurp "nonexistent_clojol_test_file_xyz.txt")))
    (let [f (reify clojure.lang.IFn
              (invoke [_ x] (* x 2))
              (invoke [_ x y] (+ x y)))]
      (is (= 10 (f 5)))
      (is (= 9 (f 4 5)))))

  (testing "Wave 2 ecosystem compatibility regressions"
    ;; Inner-class $ import, return value, and ClassNotFoundException
    (is (= java.util.Map$Entry (import 'java.util.Map$Entry)))
    (is (= java.util.Map$Entry (resolve 'Map$Entry)))
    (is (thrown? ClassNotFoundException (import 'no.such.pkg.NonExistentClojolClass)))
    (is (= clojure.lang.MapEntry (import '[clojure.lang MapEntry])))
    (is (= clojure.lang.MapEntry (resolve 'MapEntry)))

    ;; Reader: #(...) in syntax-quote, empty `{}, #=(...), #^meta, suppressed #?(:cljs #js ...)
    (let [f (eval `(let [k# 10] #(+ % k#)))]
      (is (= 15 (f 5))))
    (is (= {} (eval `{})))
    (is (= 7 (read-string "#=(+ 3 4)")))
    (is (thrown? RuntimeException (binding [*read-eval* false] (read-string "#=(+ 1 2)"))))
    (is (= {:tag 'String} (meta (read-string "#^{:tag String} x"))))
    (is (= :clj-ok (read-string {:read-cond :allow} "#?(:cljs #js [1 2] :clj :clj-ok)")))

    ;; definline & :inline expansion
    (eval '(do
             (definline __w2-add1 [x] `(+ ~x 1))
             (assert (= 42 (__w2-add1 41)))
             (assert (= [2 3 4] (mapv __w2-add1 [1 2 3])))))

    ;; defmulti with custom :hierarchy
    (let [h (atom (-> (make-hierarchy) (derive ::dog ::animal)))]
      (eval `(do
               (defmulti ~'__w2-speak identity :hierarchy ~h)
               (defmethod ~'__w2-speak ::animal [_#] :generic-animal)
               (assert (= :generic-animal (~'__w2-speak ::dog))))))

    ;; defprotocol JVM interface, instance?, direct (.method ...) interop, and shadowed local `fn`
    (eval '(do
             (defprotocol W2Transport
               (w2-recv [this] [this timeout])
               (w2-send [this msg]))
             (deftype W2Dummy [v]
               W2Transport
               (w2-recv [this] (.w2_recv this 99))
               (w2-recv [_ timeout] [v timeout])
               (w2-send [this msg] [v msg]))
             (let [d (W2Dummy. :x)]
               (assert (instance? cljl.core_test.W2Transport d))
               (assert (instance? clojure.lang.IType d))
               (assert (= [:x 99] (w2-recv d)))
               (assert (= [:x 7] (.w2_recv d 7)))
               (assert (= [:x :hi] (.w2_send d :hi))))
             (let [fn (fn [x] (* x 10))
                   r (reify W2Transport
                       (w2-recv [_] (fn 3))
                       (w2-recv [_ t] (fn t))
                       (w2-send [_ m] m))]
               (assert (instance? cljl.core_test.W2Transport r))
               (assert (= 30 (w2-recv r)))
               (assert (= 40 (.w2_recv r 4))))))

    ;; Tail-position recur in cond->, cond->>, some->, some->>, as->
    (let [step (fn [acc n] (cond-> acc (pos? n) (recur (dec n))))]
      (is (= :done (step :done 5))))
    (let [step (fn [n acc] (cond->> acc (pos? n) (recur (dec n))))]
      (is (= :done (step 5 :done))))
    (let [step (fn [n] (as-> n x (if (pos? x) (recur (dec x)) x)))]
      (is (= 0 (step 4))))
    (let [step (fn [n] (some-> n (#(if (pos? %) (dec %) nil)) (recur)))]
      (is (nil? (step 3))))

    ;; clojure.lang.* standalone runtime bridge & stdlib fixes
    (is (= (Class/forName "[B") (clojure.lang.RT/classForName "[B")))
    (is (= {:a 1 :b 2} (clojure.lang.RT/map (into-array Object [:a 1 :b 2]))))
    (is (= 123N (clojure.lang.BigInt/fromBigInteger (java.math.BigInteger. "123"))))
    (is (= #{1 2} (clojure.lang.PersistentHashSet/createWithCheck (into-array Object [1 2]))))
    (is (= {:a 1} (clojure.lang.PersistentHashMap/createWithCheck (into-array Object [:a 1]))))
    (is (= [1 2 3] (.cons [1 2] 3)))
    (is (instance? clojure.lang.IPersistentVector (subvec [1 2 3 4] 1 3)))
    (is (clojure.lang.Util/equiv 1 1N))
    (is (integer? (clojure.lang.Murmur3/hashInt 42)))
    (is (= :val (.-val (clojure.lang.Box. :val))))
    (is (= 42 (Compiler/eval '(+ 40 2) true)))
    (is (some? (.getDeclaredField String "CASE_INSENSITIVE_ORDER")))
    (let [lnr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "a\nb"))]
      (is (= 1 (.getLineNumber lnr)))
      (is (= (int \a) (.read lnr)))
      (is (= (int \newline) (.read lnr)))
      (is (= 2 (.getLineNumber lnr))))
    (let [sw (java.io.StringWriter.)]
      (#'clojure.core/pr-on {:a 1} sw)
      (is (= "{:a 1}" (.toString sw))))
    (is (some? @(requiring-resolve 'clojure.core.reducers/pool)))
    (is (some? (resolve 'clojure.test/*test-out*)))
    (require 'clojure.set 'clojure.main)
    (is (= #{:a} (clojure.set/union nil #{:a})))
    (is (= #{} (clojure.set/intersection nil #{:a})))
    (is (nil? (clojure.set/difference nil #{:a})))
    (is (= 42 (clojure.main/with-read-known 42)))
    (let [r (IllegalArgumentException. "inner")
          w (RuntimeException. "outer" r)]
      (is (identical? r (clojure.main/root-cause w)))))

  (testing "pr, prn, print, println, printf, newline, flush and *print-readably* parity"
    (let [make-writer (fn []
                        (let [sb (StringBuilder.)
                              flushes (atom 0)
                              w (proxy [java.io.Writer] []
                                  (write
                                    ([x] (if (string? x) (.append sb ^String x) (.append sb (char x))))
                                    ([cbuf off len] (.append sb ^chars cbuf (int off) (int len))))
                                  (flush [] (swap! flushes inc))
                                  (close [] nil))]
                          {:w w :sb sb :flushes flushes}))]
      ;; pr, print, printf MUST NOT flush (even if printf contains newline)
      (doseq [[f expected]
              [[#(pr "hi" 1) "\"hi\" 1"]
               [#(print "hi" 1) "hi 1"]
               [#(printf "%s=%d\n" "k" 7) "k=7\n"]]]
        (let [{:keys [w sb flushes]} (make-writer)]
          (binding [*out* w *flush-on-newline* true]
            (is (nil? (f))))
          (is (= expected (.toString sb)))
          (is (= 0 @flushes))))
      ;; prn, println, newline flush iff *flush-on-newline* is truthy
      (let [nl (System/lineSeparator)]
        (doseq [[f expected]
                [[#(prn "hi" 1) (str "\"hi\" 1" nl)]
                 [#(println "hi" 1) (str "hi 1" nl)]
                 [#(newline) nl]]]
          (let [{:keys [w sb flushes]} (make-writer)]
            (binding [*out* w *flush-on-newline* true]
              (is (nil? (f))))
            (is (= expected (.toString sb)))
            (is (= 1 @flushes)))
          (let [{:keys [w sb flushes]} (make-writer)]
            (binding [*out* w *flush-on-newline* false]
              (is (nil? (f))))
            (is (= expected (.toString sb)))
            (is (= 0 @flushes)))
          (let [{:keys [w sb flushes]} (make-writer)]
            (binding [*out* w *flush-on-newline* nil]
              (is (nil? (f))))
            (is (= expected (.toString sb)))
            (is (= 0 @flushes)))))
      ;; flush always flushes even when *flush-on-newline* is false
      (let [{:keys [w flushes]} (make-writer)]
        (binding [*out* w *flush-on-newline* false]
          (print "abc")
          (is (= 0 @flushes))
          (is (nil? (flush)))
          (is (= 1 @flushes)))))
    ;; *print-readably* respected by pr, prn, pr-str, prn-str
    (is (= "\"hi\"" (pr-str "hi")))
    (is (= "hi" (binding [*print-readably* nil] (pr-str "hi"))))
    (is (= "hi" (binding [*print-readably* false] (with-out-str (pr "hi")))))
    ;; Default *out*, *in*, and read-line on LineNumberingPushbackReader & plain Reader
    (is (instance? java.io.Writer *out*))
    (is (instance? clojure.lang.LineNumberingPushbackReader *in*))
    (is (= "line1" (binding [*in* (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "line1\nline2"))]
                     (read-line))))
    (is (= "plain" (binding [*in* (java.io.StringReader. "plain\nnext")]
                     (read-line)))))

  (testing "Wave 3 ecosystem compatibility: clojure.lang.Fn, ancestors/parents, hyphenated interop, Compiler/specials"
    (let [f (fn [x] (inc x))]
      (is (instance? clojure.lang.Fn f))
      (is (instance? clojure.lang.IFn f))
      (is (not (instance? clojure.lang.Fn :kw)))
      (is (not (instance? clojure.lang.Fn {:a 1})))
      (let [anc (ancestors (class f))]
        (is (contains? anc clojure.lang.IFn))
        (is (contains? anc clojure.lang.Fn))
        (is (contains? anc java.lang.Runnable))
        (is (contains? anc java.util.concurrent.Callable))))
    (is (contains? (parents String) Object))
    (is (contains? (parents String) CharSequence))
    (is (contains? clojure.lang.Compiler/specials 'if))
    (is (contains? clojure.lang.Compiler/specials 'let*))
    (eval '(do
             (defprotocol W3HyphenProto
               (render-item [this ctx]))
             (deftype W3Node [label]
               W3HyphenProto
               (render-item [_ ctx] (str label ":" (:v ctx))))
             (is (= "ok:42" (.render-item (W3Node. "ok") {:v 42}))))))

  (testing "Wave 4 compatibility: self-ref def, :ns/strs, update-in [], *print-namespace-maps*, resolve/ns-resolve env, Compiler/CHAR_MAP, pprint/write-out"
    ;; Self-referential anonymous fn in def (intern before compiling init)
    (eval '(do
             (def __w4-self-fact (fn [n] (if (<= n 1) 1 (* n (__w4-self-fact (dec n))))))
             (assert (= 120 (__w4-self-fact 5)))))
    ;; Namespaced :ns/strs and :ns/syms destructuring
    (let [{:baz/strs [a b] :qux/syms [c]} {"baz/a" 1 "baz/b" 2 'qux/c 3}]
      (is (= [1 2 3] [a b c])))
    ;; update-in / assoc-in with empty path
    (is (= {:a 1, nil 99} (update-in {:a 1} [] (constantly 99))))
    ;; *print-namespace-maps*
    (is (false? *print-namespace-maps*))
    (is (= "{:foo/a 1}" (pr-str {:foo/a 1})))
    (is (= "#:foo{:a 1}" (binding [*print-namespace-maps* true] (pr-str {:foo/a 1}))))
    (let [m {:foo/a 1 :foo/b 2}]
      (is (= m (read-string (binding [*print-namespace-maps* true] (pr-str m))))))
    ;; 2-arg resolve and 3-arg ns-resolve with lexical &env map
    (is (var? (resolve {} '+)))
    (is (nil? (resolve {'+ :local} '+)))
    (is (var? (ns-resolve 'clojure.core {} '+)))
    (is (nil? (ns-resolve 'clojure.core {'+ :local} '+)))
    ;; Compiler CHAR_MAP, munge/demunge, dynamic vars, ObjExpr/ObjMethod
    (is (= "_QMARK_" (get clojure.lang.Compiler/CHAR_MAP \?)))
    (is (= "foo_bar_QMARK_" (clojure.lang.Compiler/munge "foo-bar?")))
    (is (= "foo-bar?" (clojure.lang.Compiler/demunge "foo_bar_QMARK_")))
    (is (instance? clojure.lang.Var clojure.lang.Compiler/LOCAL_ENV))
    (is (some? (clojure.lang.Compiler$ObjMethod. (clojure.lang.Compiler$ObjExpr. nil) nil)))
    ;; clojure.pprint/simple-dispatch as extensible MultiFn + write-out
    (require '[clojure.pprint :as pp])
    (eval '(do
             (defrecord W4PpRec [v])
             (defmethod clojure.pprint/simple-dispatch W4PpRec [x]
               (.write *out* (str "<W4:" (:v x) ">")))
             (is (= "<W4:7>" (with-out-str (clojure.pprint/write-out (->W4PpRec 7))))))))

  (testing "Wave 5 compatibility: BigDecimal =, #_ strip in namespaced maps, syntax-quote Class./Class/m, prefix-list require, Var meta, Namespace str, clojure.string CharSequence, conj nil on maps, IFn deftype Var call, ITransient interfaces"
    ;; BigDecimal scale-sensitive = vs ==
    (is (false? (= 1.0M 1.00M)))
    (is (true? (== 1.0M 1.00M)))
    (is (true? (= 1.0M 1.0M)))
    ;; :_/ and _/ stripping in namespaced maps
    (is (= {:a/x 1, :y 2, 'z 3} (read-string "#:a{:x 1 :_/y 2 _/z 3}")))
    ;; Syntax-quote resolves ClassName. and ClassName/staticMember
    (is (= 'java.lang.StringBuilder. `StringBuilder.))
    (is (= 'java.lang.String/valueOf `String/valueOf))
    ;; Prefix-list vector :require & clojure.string CharSequence / Symbol & clojure.java.io/make-parents
    (eval '(require '[clojure [string :as w5str] [set :as w5set]] '[clojure.java.io :as w5io]))
    (eval '(do
             (is (= "ABC" (w5str/upper-case "abc")))
             (is (= #{1 2 3} (w5set/union #{1 2} #{2 3})))
             (is (= "Hello" (w5str/capitalize 'hello)))
             (is (= "abc" (w5str/lower-case (StringBuilder. "AbC"))))
             (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir") (str "cljl-w5-" (System/nanoTime)))
                   nested (java.io.File. (java.io.File. tmp-dir "sub") "leaf.txt")]
               (try
                 (w5io/make-parents nested)
                 (is (.isDirectory (.getParentFile nested)))
                 (finally
                   (.delete (.getParentFile nested))
                   (.delete tmp-dir))))))
    ;; Var meta lazily includes :ns and :name; Namespace.toString is bare name
    (let [m (meta #'inc)]
      (is (= (find-ns 'clojure.core) (:ns m)))
      (is (= 'inc (:name m))))
    (is (= "clojure.core" (str (find-ns 'clojure.core))))
    ;; conj nil onto hash-map, sorted-map, and defrecord is a no-op
    (is (= {:a 1} (conj {:a 1} nil)))
    (is (= {:a 1} (conj (sorted-map :a 1) nil)))
    (eval '(do
             (defrecord W5Rec [x])
             (is (= (->W5Rec 1) (conj (->W5Rec 1) nil)))
             (is (= (assoc (->W5Rec 1) :y 2) (conj (->W5Rec 1) {:y 2})))))
    ;; deftype implementing clojure.lang.IFn bound to a Var (interpreter + JIT hot loop)
    (eval '(do
             (deftype W5Counter [a]
               clojure.lang.IFn
               (invoke [_] (swap! a inc))
               (invoke [_ n] (swap! a + n)))
             (def w5-ctr (W5Counter. (atom 0)))
             (is (= 1 (w5-ctr)))
             (is (= 6 (w5-ctr 5)))
             (defn w5-hot [iters]
               (loop [i 0]
                 (if (< i iters)
                   (do (w5-ctr 1) (recur (inc i)))
                   @(.a w5-ctr))))
             (is (= 106 (w5-hot 100)))))
    ;; Real ITransient* interface hierarchy (persistent collections are NOT transients!)
    (is (false? (instance? clojure.lang.ITransientCollection {:a 1})))
    (is (false? (instance? clojure.lang.ITransientCollection [1 2])))
    (is (false? (instance? clojure.lang.ITransientCollection #{1 2})))
    (is (false? (instance? clojure.lang.ITransientCollection "str")))
    (let [tm (transient {:a 1})
          tv (transient [10 20])
          ts (transient #{:x})]
      (is (true? (instance? clojure.lang.ITransientCollection tm)))
      (is (true? (instance? clojure.lang.ITransientAssociative tm)))
      (is (true? (instance? clojure.lang.ITransientMap tm)))
      (is (= 1 (get tm :a)))
      (is (= :miss (get tm :z :miss)))
      (is (true? (instance? clojure.lang.ITransientCollection tv)))
      (is (true? (instance? clojure.lang.ITransientAssociative tv)))
      (is (true? (instance? clojure.lang.ITransientVector tv)))
      (is (= 20 (get tv 1)))
      (is (true? (instance? clojure.lang.ITransientCollection ts)))
      (is (true? (instance? clojure.lang.ITransientSet ts)))
      (is (= {:a 1 :b 2} (persistent! (conj! tm [:b 2]))))
      (is (= [10 99] (persistent! (assoc! tv 1 99))))
      (is (= #{:x :y} (persistent! (conj! ts :y))))))

  (testing "Wave 6 compatibility: special forms unshadowed by locals, reader cond trailing skip, Future deref/realized?, def runtime metadata eval, io/IOFactory & Coercions, reflect/JavaReflector & :ancestors, RT/makeClassLoader"
    ;; Special forms in operator position are never shadowed by local bindings
    (is (= 2 (let [if +] (if 1 2 3))))
    (is (= 10 (let [let* +] (let* [x 10] x))))
    (eval '(do
             (defn w6-self-let-test [[a b]]
               (let [let* +]
                 (let [[x y] [a b]]
                   (+ x y))))
             (is (= 7 (w6-self-let-test [3 4])))
             (defmacro let* [bindings & body]
               (reduce (fn [acc [l r]] `((fn [~l] ~acc) ~r))
                       `(do ~@body)
                       (reverse (partition 2 bindings))))
             (is (= 30 (let* [a 10 b 20] (+ a b))))))
    ;; Reader conditional skips trailing forms once a branch matches (matches Clojure ConditionalReader)
    (is (= 42 (read-string {:read-cond :allow} "#?(:clj 42 :cljs foo bar)")))
    (is (thrown? Exception (read-string {:read-cond :allow} "#?(:cljs foo :clj)")))
    ;; deref / @ and realized? on java.util.concurrent.Future / CompletableFuture (interp + JIT + timeout)
    (let [cf (java.util.concurrent.CompletableFuture/completedFuture 42)]
      (is (true? (realized? cf)))
      (is (= 42 @cf))
      (is (= 42 (deref cf)))
      (is (= 42 (deref cf 50 :timeout)))
      (let [hot (fn [f n]
                  (loop [i 0 acc 0]
                    (if (< i n)
                      (recur (inc i) (+ acc @f))
                      acc)))]
        (is (= 4200 (hot cf 100)))))
    (let [pending (java.util.concurrent.CompletableFuture.)]
      (is (false? (realized? pending)))
      (is (= :to (deref pending 5 :to)))
      (.complete pending 99)
      (is (true? (realized? pending)))
      (is (= 99 @pending)))
    ;; def / defn runtime evaluation of symbol metadata
    (eval '(do
             (def ^{:computed (+ 10 20) :ref #'inc :nested {:vars #{#'dec}}} __w6-mvar 7)
             (let [m (meta #'__w6-mvar)]
               (is (= 30 (:computed m)))
               (is (= #'inc (:ref m)))
               (is (every? var? (get-in m [:nested :vars]))))
             (defn ^{:pass-info {:depends #{#'inc}}} __w6-mfn [x] x)
             (let [m (meta #'__w6-mfn)]
               (is (= '([x]) (:arglists m)))
               (is (every? var? (get-in m [:pass-info :depends]))))
             (defn __w6-custom-args {:arglists '([a] [a b])} [& xs] xs)
             (is (= '([a] [a b]) (:arglists (meta #'__w6-custom-args))))))
    ;; clojure.java.io IOFactory & Coercions protocols + default-streams-impl
    (eval '(require '[clojure.java.io :as w6io]))
    (eval '(do
             (deftype W6MemSource [s])
             (extend W6MemSource
               w6io/IOFactory
               (assoc w6io/default-streams-impl
                 :make-input-stream (fn [^W6MemSource this _]
                                      (java.io.ByteArrayInputStream. (.getBytes ^String (.s this) "UTF-8")))))
             (let [src (W6MemSource. "hello-iofactory")]
               (is (= "hello-iofactory" (with-open [r (w6io/reader src)] (.readLine r))))
               (is (= "hello-iofactory" (with-open [is (w6io/input-stream src)]
                                          (String. (.readAllBytes is) "UTF-8")))))))
    ;; clojure.reflect Reflector / JavaReflector / records / :ancestors / array resolve-class
    (eval '(require '[clojure.reflect :as w6ref]))
    (eval '(do
             (let [cl (clojure.lang.RT/baseLoader)
                   r (w6ref/type-reflect String :ancestors true :reflector (w6ref/->JavaReflector cl))]
               (is (contains? (:bases r) 'java.lang.Object))
               (is (contains? (:bases r) 'java.lang.CharSequence))
               (is (some #(instance? clojure.reflect.Method %) (:members r)))
               (is (= (Class/forName "[I") (w6ref/resolve-class cl 'int<>)))
               (is (= (Class/forName "[Ljava.lang.String;") (w6ref/resolve-class cl 'java.lang.String<>))))))
    ;; RT/makeClassLoader
    (is (instance? clojure.lang.DynamicClassLoader (clojure.lang.RT/makeClassLoader)))
    ;; JIT-compiled (case sym ...) must use value equality, never identity (Symbol is not interned!)
    (let [f (fn [x]
              (case x
                do :do
                if :if
                let* :let*
                recur :recur
                :other))]
      (dotimes [_ 80]
        (is (= :if (f (symbol "if"))))
        (is (= :if (f (with-meta 'if {:line 99}))))
        (is (= :let* (f (symbol "let*"))))
        (is (= :other (f 'nope)))))
    ;; let/loop/fn/when-let/doseq macroexpand-1 destructures at macro time so let* only sees symbols
    (let [ex (macroexpand-1 '(let [[a b] xs] (+ a b)))]
      (is (= 'let* (first ex)))
      (is (every? symbol? (take-nth 2 (second ex)))))
    (let [ex (macroexpand-1 '(loop [[a b] xs c (+ a 1)] (recur [a b] c)))]
      (is (= 'let* (first ex)))
      (is (every? symbol? (take-nth 2 (second ex)))))
    (let [f (fn [[x y]] {:pre [(pos? x)] :post [(> % x)]} (+ x y))]
      (is (= 7 (f [3 4])))
      (is (thrown? AssertionError (f [-1 5])))
      (is (thrown? AssertionError (f [3 -1]))))
    ;; &env contains real Compiler$LocalBinding objects
    (eval '(defmacro __w6-env-check [sym]
             (let [lb (get &env sym)]
               `[~(instance? clojure.lang.Compiler$LocalBinding lb)
                 ~(.hasJavaClass ^clojure.lang.Compiler$LocalBinding lb)
                 ~(.getJavaClass ^clojure.lang.Compiler$LocalBinding lb)])))
    (eval '(let [^String s "hi"
                 plain 42]
             (is (= [true true java.lang.String] (__w6-env-check s)))
             (is (= [true false nil] (__w6-env-check plain)))))
    ;; Var/getThreadBindingFrame & resetThreadBindingFrame, Namespace/.getName, MapEquivalence, SeqIterator
    (eval '(do
             (def ^:dynamic __w6-dyn 1)
             (let [captured (binding [__w6-dyn 99] (clojure.lang.Var/getThreadBindingFrame))
                   prev (clojure.lang.Var/getThreadBindingFrame)]
               (is (= 1 __w6-dyn))
               (try
                 (clojure.lang.Var/resetThreadBindingFrame captured)
                 (is (= 99 __w6-dyn))
                 (finally
                   (clojure.lang.Var/resetThreadBindingFrame prev)))
               (is (= 1 __w6-dyn)))))
    (is (= 'clojure.core (.getName (find-ns 'clojure.core))))
    (is (instance? clojure.lang.MapEquivalence {:a 1}))
    (let [it (clojure.lang.SeqIterator. [10 20 30])]
      (is (= [10 20 30] (vec (iterator-seq it))))))

  (testing "Wave 7 compatibility: Fn toString format, nil-safe clojure.zip & path, clojure.data protocols, definterface hyphen munging & dynamic import, ArrayManager/ams/mk-am/VecNode/Delay, reducers ForkJoin, IPersistentVector vector?"
    ;; Fn.toString uses official Clojure ns$munged@hex format (needed by expound / demunge)
    (is (some? (re-matches #"clojure\.core\$int_QMARK_@[0-9a-f]+" (str int?))))
    (is (some? (re-matches #".*\$fn__\d+@[0-9a-f]+" (str (fn [x] x)))))
    (eval '(do
             (defn __w7-named-fn [x] x)
             (is (some? (re-matches #".*\$__w7_named_fn@[0-9a-f]+" (str __w7-named-fn))))))
    ;; Nil-safe clojure.zip and official (zip/path loc) returning ancestor nodes vector
    (eval '(require '[clojure.zip :as w7z]))
    (eval '(do
             (is (nil? (w7z/node nil)))
             (is (nil? (w7z/path nil)))
             (is (nil? (w7z/right nil)))
             (is (nil? (w7z/left nil)))
             (is (nil? (w7z/up nil)))
             (is (nil? (w7z/down nil)))
             (is (false? (w7z/end? nil)))
             (let [loc (-> (w7z/vector-zip [1 [2 3]]) w7z/down w7z/right w7z/down)]
               (is (= 2 (w7z/node loc)))
               (is (= [[1 [2 3]] [2 3]] (w7z/path loc))))))
    ;; clojure.data EqualityPartition & Diff protocols
    (eval '(require '[clojure.data :as w7d]))
    (eval '(do
             (is (= :map (w7d/equality-partition {:a 1})))
             (is (= :set (w7d/equality-partition #{1})))
             (is (= :sequential (w7d/equality-partition [1 2])))
             (is (= :atom (w7d/equality-partition 42)))
             (is (= [{:a 1} {:a 2} {:b 3}] (w7d/diff {:a 1 :b 3} {:a 2 :b 3})))))
    ;; definterface in hyphenated namespace munges '-' to '_' and resolves across namespaces
    (let [saved *ns*]
      (try
        (eval '(do
                 (in-ns 'w7-hyphen.iface-ns)
                 (clojure.core/refer-clojure)
                 (definterface W7TypedIface
                   (^boolean flag [^int n]))))
        (eval '(do
                 (in-ns 'w7-hyphen.consumer-ns)
                 (clojure.core/refer-clojure)
                 (import '(w7_hyphen.iface_ns W7TypedIface))))
        (eval '(let [obj (reify W7TypedIface (flag [_ n] (> n 0)))]
                 (clojure.test/is (true? (.flag obj 5)))
                 (clojure.test/is (false? (.flag obj 0)))))
        (finally
          (in-ns (.getName saved)))))
    ;; ArrayManager / ams / mk-am / EMPTY-NODE / VecNode / clojure.lang.Delay
    (is (instance? clojure.core.VecNode clojure.core/EMPTY-NODE))
    (is (instance? clojure.core.ArrayManager (:long clojure.core/ams)))
    (let [^clojure.core.ArrayManager am (:int clojure.core/ams)
          arr (.array am 3)]
      (.aset am arr 1 77)
      (is (= 3 (.alength am arr)))
      (is (= 77 (.aget am arr 1))))
    (is (instance? clojure.lang.Delay (delay 1)))
    ;; vector? recognizes IPersistentVector (e.g. vector-of)
    (is (true? (vector? (vector-of :int 1 2 3))))
    ;; clojure.core.reducers ForkJoin helpers
    (eval '(require '[clojure.core.reducers :as w7r]))
    (eval '(is (= 42 (w7r/fjinvoke #(w7r/fjjoin (w7r/fjfork (w7r/fjtask (fn [] 42)))))))))

  (testing "Wave 8 compatibility: with-precision *math-context*, Reflector sentinel & getMethods, Var$Unbound & hasRoot/isPublic/toSymbol, deftype mutable field vs 0-arg method, IRef extends IDeref, imap-cons & system-newline"
    ;; with-precision / *math-context* respected by BigDecimal ops
    (is (= 0.33333M (with-precision 5 (/ 1M 3M))))
    (is (= 0.6666M (with-precision 4 :rounding FLOOR (/ 2M 3M))))
    (is (= 1.24M (with-precision 3 (+ 1.234M 0.001M))))
    (is (= 1.51M (with-precision 3 (* 1.23M 1.23M))))
    ;; clojure.lang.Reflector stays cached across repeated lookups (no NOT_A_CLASS collision) + getMethods
    (is (= clojure.lang.Reflector (cljl.Interop/findClass "clojure.lang.Reflector")))
    (is (= clojure.lang.Reflector (cljl.Interop/findClass "clojure.lang.Reflector")))
    (is (pos? (count (clojure.lang.Reflector/getMethods String 0 "length" false))))
    ;; Var.hasRoot / isPublic / toSymbol and Var$Unbound semantics (deref returns Unbound, invoke throws)
    (eval '(do
             (def __w8-unbound)
             (is (false? (.hasRoot #'__w8-unbound)))
             (is (true? (.isPublic #'__w8-unbound)))
             (is (= 'cljl.core-test/__w8-unbound (.toSymbol #'__w8-unbound)))
             (is (instance? clojure.lang.Var$Unbound __w8-unbound))
             (is (instance? clojure.lang.Var$Unbound @#'__w8-unbound))
             (is (thrown? IllegalStateException (__w8-unbound)))
             (def ^:private __w8-priv 1)
             (is (true? (.hasRoot #'__w8-priv)))
             (is (false? (.isPublic #'__w8-priv)))))
    ;; deftype mutable field with same name as 0-arg interface method does not recurse infinitely
    (eval '(do
             (deftype W8MutMeta [^:volatile-mutable meta]
               clojure.lang.IMeta
               (meta [_] meta)
               clojure.lang.IReference
               (resetMeta [_ m] (set! meta m)))
             (let [x (W8MutMeta. {:a 1})]
               (is (= {:a 1} (meta x)))
               (.resetMeta x {:b 2})
               (is (= {:b 2} (meta x))))))
    ;; clojure.lang.IRef extends IDeref (works with reify, deref, and defmulti :hierarchy)
    (is (instance? clojure.lang.IRef (atom 1)))
    (is (instance? clojure.lang.IRef (ref 1)))
    (is (instance? clojure.lang.IRef (agent 1)))
    (is (instance? clojure.lang.IRef #'inc))
    (let [r (reify clojure.lang.IRef (deref [_] :from-iref))]
      (is (instance? clojure.lang.IRef r))
      (is (instance? clojure.lang.IDeref r))
      (is (= :from-iref @r)))
    ;; clojure.core/imap-cons and clojure.core/system-newline
    (is (= {:a 1 :b 2} ((var clojure.core/imap-cons) {:a 1} [:b 2])))
    (is (= (System/lineSeparator) clojure.core/system-newline))))

(def summary (run-tests))
(when (or (pos? (:fail summary)) (pos? (:error summary)))
  (System/exit 1))
