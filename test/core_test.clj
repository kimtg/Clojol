(ns cljl.core-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.edn :as edn]))

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

(def summary (run-tests))
(when (or (pos? (:fail summary)) (pos? (:error summary)))
  (System/exit 1))
