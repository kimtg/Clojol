(ns cljl.bench)

(defn sum-loop [n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (+ acc i))
      acc)))

(defn fib [n]
  (if (< n 2)
    n
    (+ (fib (- n 1)) (fib (- n 2)))))

(defn vec-bench [n]
  (let [v (loop [i 0 tv (transient [])]
            (if (< i n)
              (recur (inc i) (conj! tv i))
              (persistent! tv)))]
    (loop [i 0 acc 0]
      (if (< i n)
        (recur (inc i) (+ acc (nth v i)))
        acc))))

(defn map-bench [n]
  (let [m (loop [i 0 tm (transient {})]
            (if (< i n)
              (recur (inc i) (assoc! tm i (* i 2)))
              (persistent! tm)))]
    (loop [i 0 acc 0]
      (if (< i n)
        (recur (inc i) (+ acc (get m i)))
        acc))))

(println "--- Clojol Benchmarks ---")
(print "sum-loop(1,000,000) x3: ")
(dotimes [_ 3] (time (sum-loop 1000000)))

(print "fib(32) x2: ")
(dotimes [_ 2] (time (fib 32)))

(print "vec-bench(100,000) x2: ")
(dotimes [_ 2] (time (vec-bench 100000)))

(print "map-bench(50,000) x2: ")
(dotimes [_ 2] (time (map-bench 50000)))

(print "lazy pipeline(100,000): ")
(time (->> (range 100000) (filter even?) (map #(* % 3)) (reduce +)))
