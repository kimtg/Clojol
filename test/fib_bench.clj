(defn fib [n]
  (if (< n 2)
    n
    (+ (fib (- n 1)) (fib (- n 2)))))

(dotimes [i 3]
  (print (str "run " (inc i) ": "))
  (flush)
  (println (time (fib 40))))
