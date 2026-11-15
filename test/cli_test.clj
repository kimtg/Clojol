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

(def ^:private cljl-sh
  (.toString (.resolve repo-root "cljl.sh")))

(def ^:private cljl-jar
  (.toString (.resolve repo-root "build/cljl.jar")))

(defn- delete-tree! [^Path p]
  (when (Files/exists p (into-array java.nio.file.LinkOption []))
    (with-open [s (Files/walk p (into-array java.nio.file.FileVisitOption []))]
      (doseq [^Path f (reverse (iterator-seq (.iterator s)))]
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

(defn- run-proc
  ([cmd] (run-proc {} nil nil cmd))
  ([opts cmd] (run-proc opts nil nil cmd))
  ([opts stdin-str cmd] (run-proc opts stdin-str nil cmd))
  ([opts stdin-str _unused cmd]
   (let [pb (ProcessBuilder. ^java.util.List (vec (map str cmd)))]
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
   (run-proc opts stdin-str nil (into [cljl-sh] args))))

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
        (is (str/includes? (:out res) "1.12.6.1673"))))))

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
        (testing "-r / --repl reads from stdin and exits on :quit"
          (let [res (run-cljl {:dir tmp} "(+ 100 23)\n:quit\n" ["-i" (.toString init-script) "-r"])]
            (is (= 0 (:exit res)) (:err res))
            (is (str/includes? (:out res) "123"))))
        (testing "--report targets: none, stderr, file"
          (let [res-none (run-cljl {:dir tmp} ["--report" "none" "-e" "(throw (ex-info \"boom-none\" {}))"])]
            (is (not= 0 (:exit res-none)))
            (is (not (str/includes? (:err res-none) "Full report at:")))
            (is (not (str/includes? (:err res-none) "at cljl."))))
          (let [res-err (run-cljl {:dir tmp} ["--report" "stderr" "-e" "(throw (ex-info \"boom-err\" {}))"])]
            (is (not= 0 (:exit res-err)))
            (is (str/includes? (:err res-err) "boom-err"))
            (is (not (str/includes? (:err res-err) "Full report at:"))))
          (let [res-file (run-cljl {:dir tmp} ["--report" "file" "-e" "(throw (ex-info \"boom-file\" {}))"])]
            (is (not= 0 (:exit res-file)))
            (is (str/includes? (:err res-file) "Full report at:"))
            (when-let [[_ path-str] (re-find #"Full report at:\s*(\S+)" (:err res-file))]
              (let [rp (path-of path-str)]
                (is (Files/exists rp (into-array java.nio.file.LinkOption [])))
                (Files/deleteIfExists rp)))))
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
            (is (= "15" (str/trim out-str))))))
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
          (is (str/includes? (:out res-cmd) "OPTS=-e (+ 1 1) -e (+ 2 3)")))

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
                   (str "{:paths [\"src\"] :deps/prep-lib {:ensure \"" (.toString prep-marker) "\" :fn sub.prep/run}}"))
      (write-file! sublib-dir "src/sub/core.clj" "(ns sub.core)\n(def version \"local-sub-1\")\n")
      (write-file! sublib-dir "src/sub/prep.clj"
                   (str "(ns sub.prep)\n(defn run [_] (spit \"" (.toString prep-marker) "\" \"prepped\"))\n"))

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
                     (str "{:paths [\"" (.toString (.resolve tmp "tool-src")) "\"] :ns-default my.tool :exec-fn run :exec-args {:from :named-tool}}"))

        ;; Project src & deps.edn
        (write-file! tmp "src/proj/secret.clj" "(ns proj.secret)\n(def s 1)\n")
        (write-file! tmp "src/proj/app.clj"
                     "(ns proj.app)\n(defn -main [& args] (println \"APP_MAIN:\" (pr-str (vec args))))\n(defn greet [m] (println \"EXEC:\" (pr-str m)))\n")
        (write-file! tmp "extra/proj/extra.clj" "(ns proj.extra)\n(def ok true)\n")
        (write-file! tmp "deps.edn"
                     (str "{:paths [\"src\"]\n"
                          " :deps {local/sub {:local/root \"" (.toString sublib-dir) "\"}\n"
                          "        git/dep {:git/url \"" (.toString git-origin) "\" :git/sha \"" git-sha "\"}}\n"
                          " :aliases {:dev {:extra-paths [\"extra\"]}\n"
                          "           :run-e {:main-opts [\"-m\" \"proj.app\" \"from-alias\"]}\n"
                          "           :x-alias {:ns-default proj.app :ns-aliases {p proj.app} :exec-fn greet :exec-args {:a 1 :nested {:k 0}}}\n"
                          "           :t-alias {:extra-paths [\"" (.toString (.resolve tmp "tool-src")) "\"] :ns-default my.tool :exec-fn run :exec-args {:from :alias-tool}}}}"))

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
                                        (str "{:deps {git/dep {:git/tag \"v0.1.0\" :git/url \"" (.toString git-origin) "\"}}}"))]
              (let [grt (run-cljl {:dir tmp :env env}
                                  ["-X:deps" "git-resolve-tags" ":deps-file" (pr-str (.toString tag-deps))])]
                (is (= 0 (:exit grt)) (:err grt))
                (let [updated (edn/read-string (Files/readString tag-deps))]
                  (is (= git-sha (get-in updated [:deps 'git/dep :git/sha])))))))))
      (finally
        (delete-tree! tmp)))))

(let [res (run-tests 'cljl.cli-test)]
  (when (or (pos? (:fail res)) (pos? (:error res)))
    (System/exit 1)))
