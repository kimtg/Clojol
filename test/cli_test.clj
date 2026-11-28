(ns cljl.cli-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str])
  (:import [java.io File ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths]
           [java.util Comparator]
           [java.util.jar JarEntry JarOutputStream]
           [com.sun.net.httpserver HttpServer HttpHandler]))

(defn- path-of [& parts]
  (.toPath (File. ^String (str/join File/separator (map str parts)))))

(def ^:private repo-root
  (.normalize (.toAbsolutePath (.toPath (File. ".")))))

(def ^:private windows?
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win"))

(def ^:private cljl-sh
  (.toString (.resolve repo-root "cljl.sh")))

(def ^:private cljl-bat
  (.toString (.resolve repo-root "cljl.bat")))

(def ^:private cljl-jar
  (.toString (.resolve repo-root "build/cljl.jar")))

(def ^:private java-bin
  (let [exe (path-of (System/getProperty "java.home") "bin" (if windows? "java.exe" "java"))]
    (if (Files/isRegularFile exe (make-array java.nio.file.LinkOption 0))
      (.toString exe)
      "java")))

(def ^:private cljl-cmd
  ;; Use direct java -jar on Windows in ProcessBuilder so cmd.exe does not mangle quotes or newlines;
  ;; cljl.bat itself is verified in help-and-version-test.
  (if windows? [java-bin "-jar" cljl-jar] [cljl-sh]))

(defn- delete-tree! [^Path p]
  (when (Files/exists p (into-array java.nio.file.LinkOption []))
    (with-open [s (Files/walk p (into-array java.nio.file.FileVisitOption []))]
      (doseq [^Path f (reverse (iterator-seq (.iterator s)))]
        (let [file (.toFile f)]
          (when (and (.exists file) (not (.canWrite file)))
            (.setWritable file true)))
        (Files/deleteIfExists f)))))

(defn- write-file! [^Path base rel content]
  (let [p (.resolve base rel)]
    (Files/createDirectories (.getParent p) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString p content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    p))

(defn- create-jar! [^Path jar-path entries]
  (Files/createDirectories (.getParent jar-path) (make-array java.nio.file.attribute.FileAttribute 0))
  (with-open [jos (JarOutputStream. (Files/newOutputStream jar-path (make-array java.nio.file.OpenOption 0)))]
    (doseq [[entry-name content] entries]
      (.putNextEntry jos (JarEntry. entry-name))
      (.write jos (.getBytes content StandardCharsets/UTF_8))
      (.closeEntry jos))))

(defn- escape-win-arg [^String s]
  (if (neg? (.indexOf s (int \")))
    s
    (let [sb (StringBuilder. (+ (.length s) 8))
          n (.length s)]
      (loop [i 0 bs 0]
        (if (= i n)
          (do (dotimes [_ bs] (.append sb \\))
              (.toString sb))
          (let [c (.charAt s i)]
            (cond
              (= c \\) (recur (inc i) (inc bs))
              (= c \") (do (dotimes [_ (+ (* 2 bs) 1)] (.append sb \\))
                           (.append sb \")
                           (recur (inc i) 0))
              :else (do (dotimes [_ bs] (.append sb \\))
                        (.append sb c)
                        (recur (inc i) 0)))))))))

(defn- run-proc
  ([cmd] (run-proc {} nil nil cmd))
  ([opts cmd] (run-proc opts nil nil cmd))
  ([opts stdin-str cmd] (run-proc opts stdin-str nil cmd))
  ([opts stdin-str _unused cmd]
   (let [raw-args (vec (map str cmd))
         cmd-args (if (and windows? (not= "cmd.exe" (first raw-args)))
                    (into [(first raw-args)] (map escape-win-arg (rest raw-args)))
                    raw-args)
         pb (ProcessBuilder. ^java.util.List cmd-args)]
     (when-let [cwd (:dir opts)]
       (.directory pb (if (instance? Path cwd) (.toFile ^Path cwd) (File. (str cwd)))))
     (when-let [env-map (:env opts)]
       (let [pe (.environment pb)]
         (doseq [[k v] env-map]
           (if (nil? v)
             (.remove pe (str k))
             (.put pe (str k) (str v))))))
     (let [p (.start pb)]
       (when stdin-str
         (with-open [os (.getOutputStream p)]
           (.write os (.getBytes ^String stdin-str StandardCharsets/UTF_8))
           (.flush os)))
       (let [out-f (future (String. (.readAllBytes (.getInputStream p)) StandardCharsets/UTF_8))
             err-f (future (String. (.readAllBytes (.getErrorStream p)) StandardCharsets/UTF_8))
             exit (.waitFor p)]
         {:exit exit
          :out @out-f
          :err @err-f})))))

(defn- run-cljl
  ([args] (run-cljl {} nil args))
  ([opts args] (run-cljl opts nil args))
  ([opts stdin-str args]
   (run-proc opts stdin-str nil (into cljl-cmd args))))

(deftest help-and-version-test
  (testing "-h, -?, --help, -M -h, -- -h"
    (doseq [flag ["-h" "-?" "--help"]]
      (let [res (run-cljl [flag])]
        (is (= 0 (:exit res)) (str "failed for " flag ": " (:err res)))
        (is (str/includes? (:out res) "Usage:"))
        (is (str/includes? (:out res) "-Sdeps-file"))
        (is (str/includes? (:out res) "Programs provided by :deps alias:"))))
    (let [res (run-cljl ["-M" "--help"])]
      (is (= 0 (:exit res)))
      (is (str/includes? (:out res) "Usage:")))
    (let [res (run-cljl ["--" "-h"])]
      (is (= 0 (:exit res)))
      (is (str/includes? (:out res) "Usage:"))))
  (testing "-version and --version"
    (doseq [flag ["-version" "--version"]]
      (let [res (run-cljl [flag])]
        (is (= 0 (:exit res)))
        (is (str/includes? (:out res) "1.12.6.1673")))))
  (when windows?
    (testing "cljl.bat handles arguments containing ')' without cmd.exe syntax errors"
      (let [res (run-proc ["cmd.exe" "/c" cljl-bat "-Srepro" "-Sdeps" "{}" "-e" "(+ 1 2)"])]
        (is (= 0 (:exit res)) (:err res))
        (is (= "3" (str/trim (:out res))))))))

(deftest init-and-main-opts-test
  (let [tmp (Files/createTempDirectory "cljl-main-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [init-script (write-file! tmp "init.clj" "(def init-val 40)")
            app-ns (write-file! tmp "src/my/app.clj"
                                "(ns my.app)\n(defn -main [& args]\n  (println \"MAIN:\" (vec args) (vec *command-line-args*)))\n")
            _ (write-file! tmp "deps.edn" "{:paths [\"src\"]}")]
        (testing "-e / --eval prints non-nil values and exits without REPL"
          (let [res (run-cljl {:dir tmp} ["-e" "(+ 1 2) nil (* 3 4)"])]
            (is (= 0 (:exit res)))
            (is (= ["3" "12"] (str/split-lines (str/trim (:out res)))))))
        (testing "--eval and -i / --init chain state cleanly"
          (let [res (run-cljl {:dir tmp} ["-i" (.toString init-script) "--eval" "(+ init-val 2)"])]
            (is (= 0 (:exit res)))
            (is (= "42" (str/trim (:out res))))))
        (testing "-m / --main runs -main with args and *command-line-args*"
          (let [res (run-cljl {:dir tmp} ["-m" "my.app" "a" "b"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "MAIN: [a b] [a b]" (str/trim (:out res))))))
        (testing "--main with nil *command-line-args* when no trailing args"
          (let [res (run-cljl {:dir tmp} ["--main" "my.app"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "MAIN: [] []" (str/trim (:out res))))))
        (testing "script path and stdin -"
          (let [script (write-file! tmp "run_me.clj" "(println \"ARG0:\" (first *command-line-args*))")]
            (let [res (run-cljl {:dir tmp} [(.toString script) "hello"])]
              (is (= 0 (:exit res)))
              (is (= "ARG0: hello" (str/trim (:out res))))))
          (let [res (run-cljl {:dir tmp} "(println (+ 10 20))\n" ["-"])]
            (is (= 0 (:exit res)))
            (is (= "30" (str/trim (:out res))))))
        (testing "--report targets: default, none, stderr, file (all report to stderr directly, no external file)"
          (let [res-def (run-cljl {:dir tmp} ["-e" "(throw (ex-info \"boom-default\" {}))"])]
            (is (not= 0 (:exit res-def)))
            (is (str/includes? (:err res-def) "boom-default"))
            (is (str/includes? (:err res-def) "at "))
            (is (not (str/includes? (:err res-def) "Full report at:"))))
          (let [res-none (run-cljl {:dir tmp} ["--report" "none" "-e" "(throw (ex-info \"boom-none\" {}))"])]
            (is (not= 0 (:exit res-none)))
            (is (not (str/includes? (:err res-none) "Full report at:")))
            (is (not (str/includes? (:err res-none) "at cljl."))))
          (let [res-err (run-cljl {:dir tmp} ["--report" "stderr" "-e" "(throw (ex-info \"boom-err\" {}))"])]
            (is (not= 0 (:exit res-err)))
            (is (str/includes? (:err res-err) "boom-err"))
            (is (str/includes? (:err res-err) "at "))
            (is (not (str/includes? (:err res-err) "Full report at:"))))
          (let [res-file (run-cljl {:dir tmp} ["--report" "file" "-e" "(throw (ex-info \"boom-file\" {}))"])]
            (is (not= 0 (:exit res-file)))
            (is (str/includes? (:err res-file) "boom-file"))
            (is (str/includes? (:err res-file) "at "))
            (is (not (str/includes? (:err res-file) "Full report at:")))))
        (testing "-- stops clj option parsing and passes remaining args to clojure.main"
          (let [res (run-cljl {:dir tmp} ["--report" "-not-consumed-as-clj"])]
            (is (not= 0 (:exit res))))
          (let [res (run-cljl {:dir tmp} ["--" "-e" "(+ 20 22)"])]
            (is (= 0 (:exit res)))
            (is (= "42" (str/trim (:out res))))))
        (let [script (write-file! tmp "echo_args.clj" "(println (pr-str (vec *command-line-args*)))")]
          (let [res (run-cljl {:dir tmp} ["--" (.toString script) "-Spath" "-J-Xmx128m"])]
            (is (= 0 (:exit res)))
            (is (= "[\"-Spath\" \"-J-Xmx128m\"]" (str/trim (:out res))))))
        (testing "clojure.main/main callable from Clojure code"
          (let [out-str (with-out-str (main/main "-e" "(+ 7 8)"))]
            (is (= "15" (str/trim out-str)))))
        (testing "-M -m clojure.main executes clojure.main/-main"
          (let [res (run-cljl {:dir tmp} ["-M" "-m" "clojure.main" "-e" "(+ 1 2)"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "3" (str/trim (:out res))))))
        (testing "-M -i / -e defining namespace + -main then -m <ns>"
          (let [defs-file (write-file! tmp "defs.clj" "(ns inline.ns)\n(defn -main [& args] (println \"INLINE:\" (vec args)))\n")
                res1 (run-cljl {:dir tmp} ["-M" "-i" (.toString defs-file) "-m" "inline.ns" "x" "y"])
                res2 (run-cljl {:dir tmp} ["-M" "-e" "(ns inline.eval)\n(defn -main [x] (println \"EVAL:\" x))" "-m" "inline.eval" "hi"])]
            (is (= 0 (:exit res1)) (:err res1))
            (is (= "INLINE: [x y]" (str/trim (:out res1))))
            (is (= 0 (:exit res2)) (:err res2))
            (is (= ["#'inline.eval/-main" "EVAL: hi"] (str/split-lines (str/trim (:out res2)))))))
        (testing "-M -m supports .cljc namespace files"
          (write-file! tmp "src/my/cljc_app.cljc" "(ns my.cljc-app)\n(defn -main [& args] (println \"CLJC:\" #?(:clj :jvm :default :other) (vec args)))\n")
          (let [res (run-cljl {:dir tmp} ["-M" "-m" "my.cljc-app" "ok"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "CLJC: :jvm [ok]" (str/trim (:out res))))))
        (testing "strict deps.edn project does not leak CWD namespace not in :paths"
          (write-file! tmp "hidden.clj" "(ns hidden)\n(defn -main [] (println \"LEAKED\"))\n")
          (let [res (run-cljl {:dir tmp} ["-M" "--report" "none" "-m" "hidden"])]
            (is (not= 0 (:exit res))))))
      (finally
        (delete-tree! tmp)))))

(deftest dash-m-without-deps-edn-test
  (let [tmp (Files/createTempDirectory "cljl-no-deps-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cfg-dir (.resolve tmp "cfg")]
    (try
      (write-file! cfg-dir "deps.edn" "{:aliases {:user-only {:extra-paths [\"u\"]}}}")
      (write-file! tmp "foo.clj" "(ns foo)\n(defn -main [& args] (println \"CWD_FOO:\" (vec args)))\n")
      (write-file! tmp "src/my/sub_app.clj" "(ns my.sub-app)\n(defn -main [& args] (println \"SRC_SUB:\" (vec args)))\n")
      (write-file! tmp "lib/lib_ns.cljc" "(ns lib-ns)\n(defn -main [& args] (println \"LIB_CLJC:\" (vec args)))\n")
      (testing "-M -m loads from CWD, src/, and lib/ when no deps.edn is present"
        (let [r1 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}} ["-M" "-m" "foo" "a" "b"])
              r2 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}} ["-M" "-m" "my.sub-app" "1"])
              r3 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}} ["-M" "-m" "lib-ns" "z"])]
          (is (= 0 (:exit r1)) (:err r1))
          (is (= "CWD_FOO: [a b]" (str/trim (:out r1))))
          (is (= 0 (:exit r2)) (:err r2))
          (is (= "SRC_SUB: [1]" (str/trim (:out r2))))
          (is (= 0 (:exit r3)) (:err r3))
          (is (= "LIB_CLJC: [z]" (str/trim (:out r3))))))
      (finally
        (delete-tree! tmp)))))

(deftest clj-opts-and-jvm-opts-test
  (let [tmp (Files/createTempDirectory "cljl-cljopts-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cfg-dir (.resolve tmp "cfg")
        jar-path (.resolve tmp "libs/custom.jar")]
    (try
      (create-jar! jar-path {"res/hello.clj" "(ns res.hello)\n(def msg \"from-jar\")\n"
                             "hello_res.clj" "(println \"FROM_RESOURCE\")\n"})
      (write-file! cfg-dir "deps.edn" "{:aliases {:user-alias {:extra-paths [\"user-extra\"]}}}")
      (write-file! tmp "deps.edn" "{:paths [\"src\"] :aliases {:local {:extra-paths [\"extra\"] :main-opts [\"-e\" \"(+ 1 1)\"] :jvm-opts [\"-Xmx256m\"]}}}")
      (write-file! tmp "custom-deps.edn" "{:paths [\"alt-src\"]}")
      (write-file! tmp "extra-deps.edn" "{:extra-paths [\"from-file-sdeps\"]}")
      (write-file! tmp "src/app/core.clj" "(ns app.core)\n(def origin \"src\")\n")
      (write-file! tmp "alt-src/alt/core.clj" "(ns alt.core)\n(def origin \"alt-src\")\n")

      (testing "-Jopt via cljl.sh and direct java -jar (relaunch)"
        (let [res1 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                             ["-J-Dcljl.test.prop=abc" "-J" "-Xmx256m"
                              "-e" "[(System/getProperty \"cljl.test.prop\") (quot (.maxMemory (Runtime/getRuntime)) (* 1024 1024))]"])]
          (is (= 0 (:exit res1)) (:err res1))
          (let [v (edn/read-string (str/trim (:out res1)))]
            (is (= "abc" (first v)))
            (is (<= (second v) 260))))
        (let [res2 (run-proc {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                             ["java" "-jar" cljl-jar "-J-Dcljl.jar.prop=xyz" "-J-Xmx256m"
                              "-e" "[(System/getProperty \"cljl.jar.prop\") (quot (.maxMemory (Runtime/getRuntime)) (* 1024 1024))]"])]
          (is (= 0 (:exit res2)) (:err res2))
          (let [v (edn/read-string (str/trim (:out res2)))]
            (is (= "xyz" (first v)))
            (is (<= (second v) 260)))))

      (testing "-Sdeps inline EDN and file, -Sdeps-file, -Srepro, -Spath, -Sverbose, -Sthreads, -Strace, -Sforce"
        (let [res-path (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                 ["-Sverbose" "-Sthreads" "2" "-Strace" "-Sforce"
                                  "-Sdeps" "{:paths [\"sdeps-dir\"]}" "-Spath"])]
          (is (= 0 (:exit res-path)) (:err res-path))
          (is (str/includes? (:out res-path) "threads      = 2"))
          (is (str/includes? (:out res-path) "force        = true"))
          (is (str/includes? (:out res-path) (.toString (.resolve tmp "sdeps-dir"))))
          (let [trace-file (.resolve tmp "trace.edn")]
            (is (Files/exists trace-file (make-array java.nio.file.LinkOption 0)))
            (let [tr (edn/read-string (Files/readString trace-file))]
              (is (map? tr))
              (is (contains? tr :log))
              (is (contains? tr :basis)))))

        (let [res-deps-file (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                      ["-Sdeps-file" (.toString (.resolve tmp "custom-deps.edn")) "-e" "(require 'alt.core) alt.core/origin"])]
          (is (= 0 (:exit res-deps-file)) (:err res-deps-file))
          (is (= "\"alt-src\"" (str/trim (:out res-deps-file)))))

        (let [res-repro (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                  ["-Srepro" "-Sverbose" "-X:deps" "aliases"])]
          (is (= 0 (:exit res-repro)) (:err res-repro))
          (is (not (str/includes? (:out res-repro) ":user-alias")))
          (is (str/includes? (:out res-repro) ":local")))

        (let [res-cp (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                               ["-Scp" (.toString jar-path) "-e" "(require 'res.hello) res.hello/msg"])]
          (is (= 0 (:exit res-cp)) (:err res-cp))
          (is (= "\"from-jar\"" (str/trim (:out res-cp)))))

        (let [res-cp-shadow (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                      ["-Scp" (.toString jar-path) "-e" "(require 'app.core)"])]
          (is (not= 0 (:exit res-cp-shadow))))

        (let [res-jar-res (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                    ["-Scp" (.toString jar-path) "hello_res.clj"])]
          (is (= 0 (:exit res-jar-res)) (:err res-jar-res))
          (is (= "FROM_RESOURCE" (str/trim (:out res-jar-res)))))

        (let [res-cmd (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                                ["-Scommand" "echo CP={{classpath}} OPTS={{main-opts}}"
                                 "-M:local" "-e" "(+ 2 3)"])]
          (is (= 0 (:exit res-cmd)) (:err res-cmd))
          (is (str/includes? (:out res-cmd) "CP="))
          (is (str/includes? (:out res-cmd) (if windows?
                                              "OPTS=-e \"(+ 1 1)\" -e \"(+ 2 3)\""
                                              "OPTS=-e (+ 1 1) -e (+ 2 3)"))))

        (let [res-p (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir}}
                              ["-P" "-M:local" "-e" "(throw (ex-info \"should-not-run\" {}))"])]
          (is (= 0 (:exit res-p)) (:err res-p))
          (is (= "" (str/trim (:out res-p))))
          (is (Files/isDirectory (.resolve tmp ".cpcache") (into-array java.nio.file.LinkOption [])))))
      (finally
        (delete-tree! tmp)))))

(deftest aliases-exec-tool-and-deps-api-test
  (let [tmp (Files/createTempDirectory "cljl-exec-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cfg-dir (.resolve tmp "cfg")
        m2-dir (.resolve tmp "m2")
        gitlibs-dir (.resolve tmp "gitlibs")
        sublib-dir (.resolve tmp "sublib")
        jar-lib-dir (.resolve tmp "sample-jar-src")
        sample-jar (.resolve tmp "sample-1.2.3.jar")
        git-origin (.resolve tmp "git-origin")
        prep-marker (.resolve tmp "prepped.txt")]
    (try
      ;; Create local/root sublib with prep-lib
      (write-file! sublib-dir "deps.edn"
                   (str "{:paths [\"src\"] :deps/prep-lib {:ensure " (pr-str (.toString prep-marker)) " :fn sub.prep/run}}"))
      (write-file! sublib-dir "src/sub/core.clj" "(ns sub.core)\n(def version \"local-sub-1\")\n")
      (write-file! sublib-dir "src/sub/prep.clj"
                   (str "(ns sub.prep)\n(defn run [_] (spit " (pr-str (.toString prep-marker)) " \"prepped\"))\n"))

      ;; Create sample jar + install via :deps mvn-install
      (create-jar! sample-jar {"mvnlib/hello.clj" "(ns mvnlib.hello)\n(defn greet [] :from-mvn-repo)\n"
                               "META-INF/maven/org.example/sample/pom.properties" "groupId=org.example\nartifactId=sample\nversion=1.2.3\n"})

      ;; Create local git repo with tag v0.1.0 for git-resolve-tags and :deps find-versions
      (write-file! git-origin "deps.edn" "{:paths [\"src\"]}")
      (write-file! git-origin "src/gitlib/core.clj" "(ns gitlib.core)\n(def who \"git-v0.1.0\")\n")
      (let [init-cmds [["git" "init"]
                       ["git" "config" "user.name" "Test"]
                       ["git" "config" "user.email" "test@example.com"]
                       ["git" "add" "."]
                       ["git" "commit" "-m" "init"]
                       ["git" "tag" "v0.1.0"]]]
        (doseq [c init-cmds]
          (let [r (run-proc {:dir git-origin} c)]
            (is (= 0 (:exit r)) (:err r)))))
      (let [git-sha (str/trim (:out (run-proc {:dir git-origin} ["git" "rev-parse" "HEAD"])))]

        ;; Create tool namespace (lives outside project src/) and named tool config
        (write-file! tmp "tool-src/my/tool.clj"
                     "(ns my.tool)\n(defn run [m] (println (pr-str [m (boolean (try (require 'proj.secret) true (catch Exception _ false)))])))\n")
        (write-file! cfg-dir "tools/mytool.edn"
                     (str "{:paths [" (pr-str (.toString (.resolve tmp "tool-src"))) "] :ns-default my.tool :exec-fn run :exec-args {:from :named-tool}}"))

        ;; Project src & deps.edn
        (write-file! tmp "src/proj/secret.clj" "(ns proj.secret)\n(def s 1)\n")
        (write-file! tmp "src/proj/app.clj"
                     "(ns proj.app)\n(defn -main [& args] (println \"APP_MAIN:\" (pr-str (vec args))))\n(defn greet [m] (println \"EXEC:\" (pr-str m)))\n")
        (write-file! tmp "extra/proj/extra.clj" "(ns proj.extra)\n(def ok true)\n")
        (write-file! tmp "deps.edn"
                     (str "{:paths [\"src\"]\n"
                          " :deps {local/sub {:local/root " (pr-str (.toString sublib-dir)) "}\n"
                          "        git/dep {:git/url " (pr-str (.toString git-origin)) " :git/sha " (pr-str git-sha) "}}\n"
                          " :aliases {:dev {:extra-paths [\"extra\"]}\n"
                          "           :run-e {:main-opts [\"-m\" \"proj.app\" \"from-alias\"]}\n"
                          "           :x-alias {:ns-default proj.app :ns-aliases {p proj.app} :exec-fn greet :exec-args {:a 1 :nested {:k 0}}}\n"
                          "           :t-alias {:extra-paths [" (pr-str (.toString (.resolve tmp "tool-src"))) "] :ns-default my.tool :exec-fn run :exec-args {:from :alias-tool}}}}"))

        (testing "-A concatenated aliases ignore :main-opts and load :extra-paths + deps"
          (let [res (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}}
                              ["-A:dev:run-e" "-e" "(require 'proj.extra 'sub.core 'gitlib.core) [proj.extra/ok sub.core/version gitlib.core/who]"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "[true \"local-sub-1\" \"git-v0.1.0\"]" (str/trim (:out res))))))

        (testing "-M concatenated aliases apply :extra-paths AND :main-opts"
          (let [res (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}}
                              ["-M:dev:run-e" "cli-arg"])]
            (is (= 0 (:exit res)) (:err res))
            (is (= "APP_MAIN: [\"from-alias\" \"cli-arg\"]" (str/trim (:out res))))))

        (testing "-X with :ns-default, :ns-aliases, :exec-fn, kpath vector, trailing map"
          (let [res (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}}
                              ["-X:x-alias" "p/greet" ":b" "2" "[:nested :k]" "99" "{:tail true}"])]
            (is (= 0 (:exit res)) (:err res))
            (let [out (edn/read-string (second (str/split (str/trim (:out res)) #"EXEC:\s*" 2)))]
              (is (= 1 (:a out)))
              (is (= 2 (:b out)))
              (is (= 99 (get-in out [:nested :k])))
              (is (= true (:tail out))))))

        (testing "-T:alias and -Tname isolate project :paths and :deps"
          (let [res1 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}}
                               ["-T:t-alias" ":arg" "1"])]
            (is (= 0 (:exit res1)) (:err res1))
            (is (= [{:from :alias-tool :arg 1} false] (edn/read-string (str/trim (:out res1))))))
          (let [res2 (run-cljl {:dir tmp :env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}}
                               ["-Tmytool" "run" ":arg" "2"])]
            (is (= 0 (:exit res2)) (:err res2))
            (is (= [{:from :named-tool :arg 2} false] (edn/read-string (str/trim (:out res2)))))))

        (testing "-Stree and -X:deps programs (aliases, list, tree, basis, prep, mvn-install, mvn-pom, git-resolve-tags, find-versions)"
          (let [env {"CLJ_CONFIG" cfg-dir "M2_REPO" m2-dir "GITLIBS" gitlibs-dir}]
            (let [stree-res (run-cljl {:dir tmp :env env} ["-Stree"])]
              (is (= 0 (:exit stree-res)) (:err stree-res))
              (is (str/includes? (:out stree-res) "local/sub"))
              (is (str/includes? (:out stree-res) "git/dep")))

            (let [aliases-res (run-cljl {:dir tmp :env env} ["-X:deps" "aliases"])]
              (is (= 0 (:exit aliases-res)) (:err aliases-res))
              (is (str/includes? (:out aliases-res) ":dev"))
              (is (str/includes? (:out aliases-res) ":deps")))

            (let [list-res (run-cljl {:dir tmp :env env} ["-X:deps" "list"])]
              (is (= 0 (:exit list-res)) (:err list-res))
              (is (str/includes? (:out list-res) "local/sub"))
              (is (str/includes? (:out list-res) "git/dep")))

            (let [tree-edn-res (run-cljl {:dir tmp :env env} ["-X:deps" "tree" ":format" ":edn"])]
              (is (= 0 (:exit tree-edn-res)) (:err tree-edn-res))
              (let [v (edn/read-string (str/trim (:out tree-edn-res)))]
                (is (vector? v))
                (is (some #(= 'local/sub (:lib %)) v))))

            (let [basis-res (run-cljl {:dir tmp :env env} ["-X:deps" "basis"])]
              (is (= 0 (:exit basis-res)) (:err basis-res))
              (let [b (edn/read-string (str/trim (:out basis-res)))]
                (is (map? b))
                (is (contains? b :libs))
                (is (contains? b :classpath-roots))))

            (let [prep-res (run-cljl {:dir tmp :env env} ["-X:deps" "prep"])]
              (is (= 0 (:exit prep-res)) (:err prep-res))
              (is (Files/exists prep-marker (into-array java.nio.file.LinkOption [])))
              (is (= "prepped" (Files/readString prep-marker))))

            (let [inst-res (run-cljl {:dir tmp :env env} ["-X:deps" "mvn-install" ":jar" (pr-str (.toString sample-jar))])]
              (is (= 0 (:exit inst-res)) (:err inst-res))
              (is (Files/isRegularFile (.resolve m2-dir "org/example/sample/1.2.3/sample-1.2.3.jar")
                                       (into-array java.nio.file.LinkOption [])))
              ;; Verify resolving the installed maven dependency via -Sdeps!
              (let [mvn-use (run-cljl {:dir tmp :env env}
                                      ["-Sdeps" "{:deps {org.example/sample {:mvn/version \"1.2.3\"}}}"
                                       "-e" "(require 'mvnlib.hello) (mvnlib.hello/greet)"])]
                (is (= 0 (:exit mvn-use)) (:err mvn-use))
                (is (= ":from-mvn-repo" (str/trim (:out mvn-use)))))
              ;; Verify mvn-pom includes Maven dependency
              (let [pom-res (run-cljl {:dir tmp :env env}
                                      ["-Sdeps" "{:deps {org.example/sample {:mvn/version \"1.2.3\"}}}"
                                       "-X:deps" "mvn-pom" ":group" "\"my.grp\"" ":artifact" "\"my-art\"" ":version" "\"0.2.0\""])]
                (is (= 0 (:exit pom-res)) (:err pom-res))
                (let [pom-xml (Files/readString (.resolve tmp "pom.xml"))]
                  (is (str/includes? pom-xml "<groupId>org.example</groupId>"))
                  (is (str/includes? pom-xml "<artifactId>sample</artifactId>"))
                  (is (str/includes? pom-xml "<version>1.2.3</version>")))))

            ;; Verify :deps find-versions on git and local HTTP Maven repo
            (let [fv-git (run-cljl {:dir tmp :env env}
                                   ["-X:deps" "find-versions" ":lib" "git/dep" ":git/url" (pr-str (.toString git-origin))])]
              (is (= 0 (:exit fv-git)) (:err fv-git))
              (is (str/includes? (:out fv-git) "{:git/tag \"v0.1.0\"}")))

            (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                  port (.getPort (.getAddress server))]
              (.createContext server "/org/example/mock/maven-metadata.xml"
                (reify HttpHandler
                  (handle [_ ex]
                    (let [body "<metadata><versioning><versions><version>0.1.0</version><version>0.2.0</version></versions></versioning></metadata>"
                          bytes (.getBytes body StandardCharsets/UTF_8)]
                      (.sendResponseHeaders ex 200 (alength bytes))
                      (with-open [os (.getResponseBody ex)]
                        (.write os bytes))))))
              (.start server)
              (try
                (let [fv-mvn (run-cljl {:dir tmp :env env}
                                       ["-Sdeps" (str "{:mvn/repos {\"mock\" {:url \"http://127.0.0.1:" port "/\"}}}")
                                        "-X:deps" "find-versions" ":lib" "org.example/mock"])]
                  (is (= 0 (:exit fv-mvn)) (:err fv-mvn))
                  (is (str/includes? (:out fv-mvn) "{:mvn/version \"0.2.0\"}")))
                (finally
                  (.stop server 0))))

            ;; Verify :deps git-resolve-tags adds :git/sha
            (let [tag-deps (write-file! tmp "tag-deps.edn"
                                        (str "{:deps {git/dep {:git/tag \"v0.1.0\" :git/url " (pr-str (.toString git-origin)) "}}}"))]
              (let [grt (run-cljl {:dir tmp :env env}
                                  ["-X:deps" "git-resolve-tags" ":deps-file" (pr-str (.toString tag-deps))])]
                (is (= 0 (:exit grt)) (:err grt))
                (let [updated (edn/read-string (Files/readString tag-deps))]
                  (is (= git-sha (get-in updated [:deps 'git/dep :git/sha])))))))))
      (finally
        (delete-tree! tmp)))))

(deftest cli-print-flush-behavior-test
  (testing "CLI subprocess stdout matches Clojure flush semantics for pr/prn/print/println/printf"
    (let [run-e (fn [expr]
                  (run-proc {:dir repo-root}
                            [java-bin "-jar" cljl-jar "-Srepro" "-Sdeps" "{}" "-e" expr]))]
      (let [r (run-e "(pr :a)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= "" (:out r))))
      (let [r (run-e "(print :a)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= "" (:out r))))
      (let [r (run-e "(printf \"%s\\n\" :a)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= "" (:out r))))
      (let [r (run-e "(binding [*flush-on-newline* false] (println :a))")]
        (is (= 0 (:exit r)) (:err r))
        (is (= "" (:out r))))
      (let [r (run-e "(prn :a)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= ":a" (str/trim (:out r)))))
      (let [r (run-e "(println :a)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= ":a" (str/trim (:out r)))))
      (let [r (run-e "(print :a) (flush)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= ":a" (:out r))))
      (let [r (run-e "(printf \"%s=%d\" \"x\" 42) (flush)")]
        (is (= 0 (:exit r)) (:err r))
        (is (= "x=42" (:out r)))))))

(deftest cli-security-hardening-test
  (testing "Security regressions: #= blocked in -X, -Tname traversal blocked, git flag injection blocked"
    ;; #= eval reader macro must be rejected in -X EDN CLI arguments
    (let [r (run-proc {:dir repo-root}
                      [java-bin "-jar" cljl-jar "-Srepro" "-Sdeps" "{}"
                       "-X" "clojure.core/prn" ":x" "#=(+ 1 2)"])]
      (is (not= 0 (:exit r)))
      (is (not (str/includes? (:out r) "3"))))
    ;; -Tname path traversal must be rejected
    (let [r (run-proc {:dir repo-root}
                      [java-bin "-jar" cljl-jar "-Srepro" "-T../../etc/passwd" "clojure.core/prn"])]
      (is (not= 0 (:exit r))))
    ;; Git URL flag injection must be rejected
    (let [r (run-proc {:dir repo-root}
                      [java-bin "-jar" cljl-jar "-Srepro" "-Sforce" "-Spath"
                       "-Sdeps" "{:deps {foo/bar {:git/url \"--upload-pack=id\" :git/sha \"deadbeef\"}}}"])]
      (is (not= 0 (:exit r))))
    ;; Maven coordinate path traversal must be rejected
    (let [r (run-proc {:dir repo-root}
                      [java-bin "-jar" cljl-jar "-Srepro" "-Sforce" "-Spath"
                       "-Sdeps" "{:deps {foo/bar {:mvn/version \"../../../evil\"}}}"])]
      (is (not= 0 (:exit r))))))

(deftest milestone-6-deps-repl-test
  (testing "6.1 Maven version comparator & symbol completion"
    (is (neg? (cljl.Deps/compareMavenVersions "1.0.0-alpha" "1.0.0-beta")))
    (is (neg? (cljl.Deps/compareMavenVersions "1.0.0-beta" "1.0.0-rc1")))
    (is (neg? (cljl.Deps/compareMavenVersions "1.0.0-rc1" "1.0.0-SNAPSHOT")))
    (is (neg? (cljl.Deps/compareMavenVersions "1.0.0-SNAPSHOT" "1.0.0")))
    (is (neg? (cljl.Deps/compareMavenVersions "1.0.0" "1.0.0-sp1")))
    (is (neg? (cljl.Deps/compareMavenVersions "1.2.0" "1.10.0")))
    (is (zero? (cljl.Deps/compareMavenVersions "1.0" "1.0.0")))
    (let [comps (vec (cljl.Main/completeSymbol "map"))]
      (is (some #(= "map" %) comps))
      (is (some #(= "mapv" %) comps))))

  (testing "6.2 BFS nearest-then-newest, BOM imports, loud errors & clojure.repl.deps basis refresh"
    (let [tmp (Files/createTempDirectory "cljl-m6-deps-" (into-array java.nio.file.attribute.FileAttribute []))
          m2-dir (.resolve tmp "m2")
          cfg-dir (.resolve tmp "cfg")
          env {"CLJ_CONFIG" (.toString cfg-dir) "M2_REPO" (.toString m2-dir)}
          put-mvn! (fn [group art ver pom-xml]
                     (let [rel (str (.replace group "." "/") "/" art "/" ver "/" art "-" ver)]
                       (write-file! m2-dir (str rel ".pom") pom-xml)
                       ;; Write an empty valid zip/jar stub
                       (let [jar-path (.resolve m2-dir (str rel ".jar"))]
                         (Files/createDirectories (.getParent jar-path) (into-array java.nio.file.attribute.FileAttribute []))
                         (with-open [zos (java.util.zip.ZipOutputStream. (Files/newOutputStream jar-path (into-array java.nio.file.OpenOption [])))]
                           (.putNextEntry zos (java.util.zip.ZipEntry. "META-INF/MANIFEST.MF"))
                           (.write zos (.getBytes "Manifest-Version: 1.0\n" "UTF-8"))
                           (.closeEntry zos)))))]
      (try
        ;; Install BOM pom managing com.m6/managed -> 3.4.5
        (write-file! m2-dir "com/m6/bom/1.0.0/bom-1.0.0.pom"
                     "<project><groupId>com.m6</groupId><artifactId>bom</artifactId><version>1.0.0</version><packaging>pom</packaging>
                        <dependencyManagement><dependencies>
                          <dependency><groupId>com.m6</groupId><artifactId>managed</artifactId><version>3.4.5</version></dependency>
                        </dependencies></dependencyManagement>
                      </project>")
        (put-mvn! "com.m6" "managed" "3.4.5"
                  "<project><groupId>com.m6</groupId><artifactId>managed</artifactId><version>3.4.5</version></project>")
        ;; Diamond: A -> C 1.0.0 & D 2.0.0; B -> C 2.0.0 & managed (via BOM!)
        (put-mvn! "com.m6" "c" "1.0.0" "<project><groupId>com.m6</groupId><artifactId>c</artifactId><version>1.0.0</version></project>")
        (put-mvn! "com.m6" "c" "2.0.0" "<project><groupId>com.m6</groupId><artifactId>c</artifactId><version>2.0.0</version></project>")
        (put-mvn! "com.m6" "d" "1.0.0" "<project><groupId>com.m6</groupId><artifactId>d</artifactId><version>1.0.0</version></project>")
        (put-mvn! "com.m6" "d" "2.0.0" "<project><groupId>com.m6</groupId><artifactId>d</artifactId><version>2.0.0</version></project>")
        (put-mvn! "com.m6" "a" "1.0.0"
                  "<project><groupId>com.m6</groupId><artifactId>a</artifactId><version>1.0.0</version>
                     <dependencies>
                       <dependency><groupId>com.m6</groupId><artifactId>c</artifactId><version>1.0.0</version></dependency>
                       <dependency><groupId>com.m6</groupId><artifactId>d</artifactId><version>2.0.0</version></dependency>
                     </dependencies>
                   </project>")
        (put-mvn! "com.m6" "b" "1.0.0"
                  "<project><groupId>com.m6</groupId><artifactId>b</artifactId><version>1.0.0</version>
                     <dependencyManagement><dependencies>
                       <dependency><groupId>com.m6</groupId><artifactId>bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency>
                     </dependencies></dependencyManagement>
                     <dependencies>
                       <dependency><groupId>com.m6</groupId><artifactId>c</artifactId><version>2.0.0</version></dependency>
                       <dependency><groupId>com.m6</groupId><artifactId>managed</artifactId></dependency>
                     </dependencies>
                   </project>")
        ;; Root depends on A (1.0.0), B (1.0.0), and D (1.0.0) directly:
        ;; - C is at depth 1 via both A (1.0.0) and B (2.0.0) -> newest (2.0.0) MUST win!
        ;; - D is at depth 0 (1.0.0) and depth 1 via A (2.0.0) -> nearest (1.0.0) MUST win!
        ;; - managed version MUST be resolved via BOM import (3.4.5)!
        (write-file! tmp "deps.edn"
                     "{:paths [\"src\"]
                       :mvn/repos {}
                       :deps {com.m6/a {:mvn/version \"1.0.0\"}
                              com.m6/b {:mvn/version \"1.0.0\"}
                              com.m6/d {:mvn/version \"1.0.0\"}}}")
        (let [r (run-cljl {:dir tmp :env env} ["-Sforce" "-Spath"])]
          (is (= 0 (:exit r)) (:err r))
          (is (str/includes? (:out r) "c-2.0.0.jar"))
          (is (not (str/includes? (:out r) "c-1.0.0.jar")))
          (is (str/includes? (:out r) "d-1.0.0.jar"))
          (is (not (str/includes? (:out r) "d-2.0.0.jar")))
          (is (str/includes? (:out r) "managed-3.4.5.jar")))

        ;; Missing artifact MUST fail loudly (non-zero exit), never silently succeed
        (let [r (run-cljl {:dir tmp :env env}
                          ["-Sforce" "-Spath"
                           "-Sdeps" "{:mvn/repos {} :deps {com.m6/does-not-exist {:mvn/version \"9.9.9\"}}}"])]
          (is (not= 0 (:exit r)))
          (is (str/includes? (:err r) "Could not find artifact")))

        ;; clojure.repl.deps/add-lib reuses single DynamicClassLoader & refreshes clojure.java.basis/current-basis
        (let [extra-dir (.resolve tmp "extra-lib")]
          (write-file! extra-dir "deps.edn" "{:paths [\"src\"]}")
          (write-file! extra-dir "src/m6extra/core.clj" "(ns m6extra.core) (def val 777)")
          (let [code (str "(require '[clojure.repl.deps :as rd] '[clojure.java.basis :as b]) "
                          "(let [cl1 (clojure.lang.RT/baseLoader)] "
                          "  (rd/add-lib 'm6/extra {:local/root " (pr-str (.toString extra-dir)) "}) "
                          "  (let [cl2 (clojure.lang.RT/baseLoader)] "
                          "    (require 'm6extra.core) "
                          "    (prn [(identical? cl1 cl2) "
                          "          (instance? clojure.lang.DynamicClassLoader cl2) "
                          "          (contains? (:libs (b/current-basis)) 'm6/extra) "
                          "          @(resolve 'm6extra.core/val)])))")
                r (run-cljl {:dir tmp :env env} ["-Sforce" "-e" code])]
            (is (= 0 (:exit r)) (:err r))
            (is (= "[true true true 777]" (str/trim (:out r))))))

        ;; Interactive REPL thread-local *1/*2/*3
        (let [pb (ProcessBuilder. ^java.util.List [java-bin "-jar" cljl-jar "-Srepro"])
              _ (.directory pb (.toFile repo-root))
              p (.start pb)]
          (with-open [os (.getOutputStream p)]
            (.write os (.getBytes "(+ 10 20)\n(* *1 2)\n(+ *1 *2)\n" "UTF-8"))
            (.flush os))
          (let [out (slurp (.getInputStream p))
                code (.waitFor p)]
            (is (= 0 code))
            (is (str/includes? out "30"))
            (is (str/includes? out "60"))
            (is (str/includes? out "90"))))

        ;; JIT flags -Dcljl.jit.automemo=false, -Dcljl.jit.cse=false, and -Dcljl.jit.tco=false
        (let [r (run-cljl {:dir tmp :env env}
                          ["-J-Dcljl.jit.automemo=false" "-J-Dcljl.jit.cse=false" "-e"
                           "(do (defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) [(fib 25) (fib -5)])"])]
          (is (= 0 (:exit r)) (:err r))
          (is (= "[75025 -5]" (str/trim (:out r)))))
        (let [r (run-cljl {:dir tmp :env env}
                          ["-J-Dcljl.jit.automemo=false" "-J-Dcljl.jit.cse=false" "-J-Dcljl.jit.tco=false" "-e"
                           "(do (defn s [n] (if (<= n 0) 0 (+ n (s (- n 1))))) (s 100))"])]
          (is (= 0 (:exit r)) (:err r))
          (is (= "5050" (str/trim (:out r)))))
        (finally
          (delete-tree! tmp))))))

(let [res (run-tests 'cljl.cli-test)]
  (when (or (pos? (:fail res)) (pos? (:error res)))
    (System/exit 1)))
