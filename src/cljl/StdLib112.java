package cljl;

import cljl.lang.*;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Official Clojure 1.12 standard library namespaces:
 * - clojure.java.basis & clojure.java.basis.impl
 * - clojure.test.junit
 * - clojure.test.tap
 * - clojure.inspector
 */
public final class StdLib112 {

    private static final Set<String> LOADED = Collections.synchronizedSet(new HashSet<>());

    private StdLib112() {}

    public static boolean ensureLoaded(String nsName) {
        return switch (nsName) {
            case "clojure.java.basis.impl" -> { initBasisImpl(); yield true; }
            case "clojure.java.basis" -> { initBasis(); yield true; }
            case "clojure.test.junit" -> { initTestJunit(); yield true; }
            case "clojure.test.tap" -> { initTestTap(); yield true; }
            case "clojure.inspector" -> { initInspector(); yield true; }
            default -> false;
        };
    }

    // ========================================================================
    // clojure.java.basis.impl & clojure.java.basis (Clojure 1.12)
    // ========================================================================

    private static volatile Object INITIAL_BASIS;
    private static final Atom CURRENT_BASIS = new Atom(null);

    public static Object computeInitialBasis() {
        Object existing = INITIAL_BASIS;
        if (existing != null) return existing;
        synchronized (StdLib112.class) {
            if (INITIAL_BASIS != null) return INITIAL_BASIS;
            Object basis = null;
            String basisProp = System.getProperty("clojure.basis");
            if (basisProp != null && !basisProp.isBlank()) {
                try {
                    Path p = Path.of(basisProp);
                    if (Files.isRegularFile(p)) {
                        String content = Files.readString(p);
                        basis = Reader.readEdnString(null, content);
                    }
                } catch (Exception ignored) {
                    // Fall back to classpath-derived basis below
                }
            }
            if (basis == null) {
                basis = buildClasspathBasis();
            }
            INITIAL_BASIS = basis;
            if (CURRENT_BASIS.deref() == null) {
                CURRENT_BASIS.reset(basis);
            }
            return basis;
        }
    }

    private static PersistentHashMap buildClasspathBasis() {
        String cp = System.getProperty("java.class.path", "");
        String[] entries = cp.isEmpty() ? new String[0] : cp.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<Object> roots = new ArrayList<>();
        PersistentHashMap cpMap = PersistentHashMap.EMPTY;
        Keyword pathKeyKw = Keyword.intern("path-key");
        Keyword pathsKw = Keyword.intern("paths");
        for (String entry : entries) {
            if (entry == null || entry.isEmpty()) continue;
            roots.add(entry);
            cpMap = cpMap.assoc(entry, PersistentHashMap.create(pathKeyKw, pathsKw));
        }
        return PersistentHashMap.create(
                Keyword.intern("libs"), PersistentHashMap.EMPTY,
                Keyword.intern("classpath"), cpMap,
                Keyword.intern("classpath-roots"), PersistentVector.create(roots),
                Keyword.intern("argmap"), PersistentHashMap.EMPTY);
    }

    public static Object currentBasis() {
        Object cur = CURRENT_BASIS.deref();
        if (cur == null) return computeInitialBasis();
        return cur;
    }

    public static void setInitialBasis(Object basis) {
        if (basis == null) return;
        synchronized (StdLib112.class) {
            INITIAL_BASIS = basis;
            CURRENT_BASIS.reset(basis);
        }
    }

    public static void mergeBasis(PersistentHashMap addedBasis) {
        if (addedBasis == null) return;
        computeInitialBasis();
        Keyword kLibs = Keyword.intern("libs");
        Keyword kCp = Keyword.intern("classpath");
        Keyword kRoots = Keyword.intern("classpath-roots");
        CURRENT_BASIS.swap(Fn.of1("merge-basis", oldObj -> {
            PersistentHashMap old = oldObj instanceof PersistentHashMap m ? m : PersistentHashMap.EMPTY;
            PersistentHashMap oldLibs = old.valAt(kLibs) instanceof PersistentHashMap m ? m : PersistentHashMap.EMPTY;
            PersistentHashMap newLibs = addedBasis.valAt(kLibs) instanceof PersistentHashMap m ? m : PersistentHashMap.EMPTY;
            for (ISeq s = newLibs.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                oldLibs = oldLibs.assoc(e.getKey(), e.getValue());
            }
            PersistentHashMap oldCp = old.valAt(kCp) instanceof PersistentHashMap m ? m : PersistentHashMap.EMPTY;
            PersistentHashMap newCp = addedBasis.valAt(kCp) instanceof PersistentHashMap m ? m : PersistentHashMap.EMPTY;
            for (ISeq s = newCp.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                oldCp = oldCp.assoc(e.getKey(), e.getValue());
            }
            LinkedHashSet<Object> roots = new LinkedHashSet<>();
            for (ISeq s = RT.seq(old.valAt(kRoots)); s != null; s = s.next()) roots.add(s.first());
            for (ISeq s = RT.seq(addedBasis.valAt(kRoots)); s != null; s = s.next()) roots.add(s.first());
            return old.assoc(kLibs, oldLibs)
                    .assoc(kCp, oldCp)
                    .assoc(kRoots, PersistentVector.create(new ArrayList<>(roots)));
        }));
    }

    public static Object updateBasis(Fn f, Object... args) {
        computeInitialBasis();
        return CURRENT_BASIS.swap(Fn.of1("update-basis-fn", old -> {
            Object[] callArgs = new Object[args.length + 1];
            callArgs[0] = old;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            return f.applyTo(callArgs);
        }));
    }

    public static synchronized void initBasisImpl() {
        if (LOADED.contains("clojure.java.basis.impl")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.basis.impl");
        LOADED.add("clojure.java.basis.impl");
        computeInitialBasis();

        ns.intern("the-basis", CURRENT_BASIS);
        ns.intern("initial-basis", Fn.of0("initial-basis", StdLib112::computeInitialBasis));
        ns.intern("current-basis", Fn.of0("current-basis", StdLib112::currentBasis));
        ns.intern("update-basis!", Fn.ofVarargs("update-basis!", args -> {
            if (args.length < 1 || !(args[0] instanceof Fn f)) {
                throw new IllegalArgumentException("update-basis! requires a function");
            }
            Object[] extra = Arrays.copyOfRange(args, 1, args.length);
            return updateBasis(f, extra);
        }));
        ns.intern("init-basis!", Fn.of1("init-basis!", basis -> {
            synchronized (StdLib112.class) {
                INITIAL_BASIS = basis;
                CURRENT_BASIS.reset(basis);
            }
            return basis;
        }));
        Core.markLibLoaded("clojure.java.basis.impl");
    }

    public static synchronized void initBasis() {
        if (LOADED.contains("clojure.java.basis")) return;
        initBasisImpl();
        Namespace ns = Namespace.findOrCreate("clojure.java.basis");
        LOADED.add("clojure.java.basis");

        ns.intern("initial-basis", Fn.of0("initial-basis", StdLib112::computeInitialBasis));
        ns.intern("current-basis", Fn.of0("current-basis", StdLib112::currentBasis));
        Core.markLibLoaded("clojure.java.basis");
    }

    // ========================================================================
    // clojure.test.junit
    // ========================================================================

    public static synchronized void initTestJunit() {
        if (LOADED.contains("clojure.test.junit")) return;
        StdLib.ensureLoaded("clojure.test");
        Namespace ns = Namespace.findOrCreate("clojure.test.junit");
        LOADED.add("clojure.test.junit");

        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (clojure.core/refer-clojure)
                (clojure.core/require '[clojure.test :as t])
                (def ^:dynamic *var-context* nil)
                (def ^:dynamic *depth* 0)

                (defn indent []
                  (dotimes [_ (* *depth* 4)] (print " ")))

                (defn escape-xml [text]
                  (let [s (str text)]
                    (-> s
                        (.replace "&" "&amp;")
                        (.replace "<" "&lt;")
                        (.replace ">" "&gt;")
                        (.replace "'" "&apos;")
                        (.replace "\\"" "&quot;"))))

                (defn start-element
                  ([tag] (start-element tag nil))
                  ([tag attrs]
                   (indent)
                   (print (str "<" (name tag)))
                   (doseq [[k v] attrs]
                     (print (str " " (name k) "=\\"" (escape-xml v) "\\"")))
                   (println ">")))

                (defn finish-element [tag]
                  (indent)
                  (println (str "</" (name tag) ">")))

                (defn element-content [content]
                  (print (escape-xml content)))

                (defn test-name [vars]
                  (apply str (interpose "." (reverse (map #(:name (meta %)) vars)))))

                (defn package-class [name-str]
                  (let [i (.lastIndexOf (str name-str) ".")]
                    (if (pos? i)
                      [(.substring (str name-str) 0 i) (.substring (str name-str) (inc i))]
                      ["" (str name-str)])))

                (defn suite-attrs [package classname]
                  (let [attrs {:name classname}]
                    (if (and package (not= "" package))
                      (assoc attrs :package package)
                      attrs)))

                (defn start-suite [name-str]
                  (let [[pkg cls] (package-class name-str)]
                    (start-element 'testsuite (suite-attrs pkg cls))))

                (defn finish-suite []
                  (finish-element 'testsuite))

                (defn start-case [name-str classname]
                  (start-element 'testcase {:name name-str :classname classname}))

                (defn finish-case []
                  (finish-element 'testcase))

                (defn failure-el [message expected actual]
                  (start-element 'failure {:message (or message "") :type "failure"})
                  (element-content
                    (str "expected: " (pr-str expected) "\\n  actual: " (pr-str actual)))
                  (println)
                  (finish-element 'failure))

                (defn error-el [message expected actual]
                  (start-element 'error {:message (or message "") :type (if (instance? Throwable actual) (.getName (class actual)) "error")})
                  (element-content
                    (str "expected: " (pr-str expected) "\\n  actual: " (pr-str actual)))
                  (println)
                  (finish-element 'error))

                (defmulti junit-report :type)

                (defmethod junit-report :default [_] nil)

                (defmethod junit-report :begin-test-ns [m]
                  (t/with-test-out
                    (let [ns-name (str (ns-name (:ns m)))]
                      (start-suite ns-name)
                      (set! *depth* (inc *depth*)))))

                (defmethod junit-report :end-test-ns [_]
                  (t/with-test-out
                    (set! *depth* (max 0 (dec *depth*)))
                    (finish-suite)))

                (defmethod junit-report :begin-test-var [m]
                  (t/with-test-out
                    (let [v (:var m)
                          ns-name (str (ns-name (:ns (meta v))))
                          vname (str (:name (meta v)))]
                      (start-case vname ns-name)
                      (set! *depth* (inc *depth*)))))

                (defmethod junit-report :end-test-var [_]
                  (t/with-test-out
                    (set! *depth* (max 0 (dec *depth*)))
                    (finish-case)))

                (defmethod junit-report :pass [_]
                  (t/inc-report-counter :pass))

                (defmethod junit-report :fail [m]
                  (t/inc-report-counter :fail)
                  (t/with-test-out
                    (failure-el (:message m) (:expected m) (:actual m))))

                (defmethod junit-report :error [m]
                  (t/inc-report-counter :error)
                  (t/with-test-out
                    (error-el (:message m) (:expected m) (:actual m))))

                (defmacro with-junit-output [& body]
                  `(t/with-test-out
                     (binding [t/report junit-report
                               *var-context* (list)
                               *depth* 1]
                       (println "<?xml version=\\"1.0\\" encoding=\\"UTF-8\\"?>")
                       (println "<testsuites>")
                       (let [result# (do ~@body)]
                         (println "</testsuites>")
                         result#))))
                """, "clojure/test/junit.clj");
        } finally {
            Var.popThreadBindings();
        }
        Core.markLibLoaded("clojure.test.junit");
    }

    // ========================================================================
    // clojure.test.tap
    // ========================================================================

    public static synchronized void initTestTap() {
        if (LOADED.contains("clojure.test.tap")) return;
        StdLib.ensureLoaded("clojure.test");
        Namespace ns = Namespace.findOrCreate("clojure.test.tap");
        LOADED.add("clojure.test.tap");

        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (clojure.core/refer-clojure)
                (clojure.core/require '[clojure.test :as t])

                (defn print-tap-plan [n]
                  (println (str "1.." n)))

                (defn print-tap-diagnostic [data]
                  (doseq [line (.split (str data) "\\r?\\n")]
                    (println (str "# " line))))

                (defn print-tap-pass [msg]
                  (if msg
                    (println (str "ok " msg))
                    (println "ok")))

                (defn print-tap-fail [msg]
                  (if msg
                    (println (str "not ok " msg))
                    (println "not ok")))

                (defn print-diagnostics [data]
                  (when (seq t/*testing-contexts*)
                    (print-tap-diagnostic (t/testing-contexts-str)))
                  (when (:message data)
                    (print-tap-diagnostic (:message data)))
                  (print-tap-diagnostic (str "expected: " (pr-str (:expected data))))
                  (print-tap-diagnostic (str "  actual: " (pr-str (:actual data)))))

                (defmulti tap-report :type)

                (defmethod tap-report :default [_] nil)

                (defmethod tap-report :pass [data]
                  (t/with-test-out
                    (t/inc-report-counter :pass)
                    (print-tap-pass (t/testing-vars-str data))))

                (defmethod tap-report :fail [data]
                  (t/with-test-out
                    (t/inc-report-counter :fail)
                    (print-tap-fail (t/testing-vars-str data))
                    (print-diagnostics data)))

                (defmethod tap-report :error [data]
                  (t/with-test-out
                    (t/inc-report-counter :error)
                    (print-tap-fail (t/testing-vars-str data))
                    (print-diagnostics data)))

                (defmethod tap-report :summary [data]
                  (t/with-test-out
                    (print-tap-plan (+ (:pass data 0) (:fail data 0) (:error data 0)))))

                (defmacro with-tap-output [& body]
                  `(t/with-test-out
                     (binding [t/report tap-report]
                       ~@body)))
                """, "clojure/test/tap.clj");
        } finally {
            Var.popThreadBindings();
        }
        Core.markLibLoaded("clojure.test.tap");
    }

    // ========================================================================
    // clojure.inspector
    // ========================================================================

    public static synchronized void initInspector() {
        if (LOADED.contains("clojure.inspector")) return;
        Namespace ns = Namespace.findOrCreate("clojure.inspector");
        LOADED.add("clojure.inspector");

        ns.intern("__headless?", Fn.of0("__headless?", GraphicsEnvironment::isHeadless));

        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (clojure.core/refer-clojure)
                (defn atom? [x]
                  (not (coll? x)))

                (defn collection-tag [x]
                  (cond
                    (instance? java.util.Map$Entry x) :entry
                    (instance? java.util.Map x) :seqable
                    (set? x) :seqable
                    (sequential? x) :seq
                    (instance? clojure.lang.Seqable x) :seqable
                    :else :atom))

                (defmulti is-leaf collection-tag)
                (defmulti get-child (fn [parent index] (collection-tag parent)))
                (defmulti get-child-count collection-tag)

                (defmethod is-leaf :default [node] (atom? node))
                (defmethod get-child :default [parent index] (nth (seq parent) index))
                (defmethod get-child-count :default [parent] (count parent))

                (defmethod is-leaf :entry [_] false)
                (defmethod get-child :entry [e index] (if (zero? index) (key e) (val e)))
                (defmethod get-child-count :entry [_] 2)

                (defmethod is-leaf :seq [_] false)
                (defmethod get-child :seq [parent index] (nth parent index))
                (defmethod get-child-count :seq [parent] (count parent))

                (defmethod is-leaf :seqable [_] false)
                (defmethod get-child :seqable [parent index] (nth (seq parent) index))
                (defmethod get-child-count :seqable [parent] (count parent))

                (defmulti list-provider class)
                (defmethod list-provider :default [x]
                  {:nrows 1 :get-value (fn [_ _] x) :get-label (fn [_] "Value")})
                (defmethod list-provider java.util.Map [m]
                  (let [ks (vec (keys m))]
                    {:nrows (count ks)
                     :get-value (fn [i] (get m (nth ks i)))
                     :get-label (fn [i] (nth ks i))}))
                (defmethod list-provider clojure.lang.Sequential [s]
                  (let [v (vec s)]
                    {:nrows (count v)
                     :get-value (fn [i] (nth v i))
                     :get-label (fn [i] i)}))

                (defn tree-model [data]
                  (proxy [javax.swing.tree.TreeModel] []
                    (getRoot [] data)
                    (isLeaf [node] (boolean (is-leaf node)))
                    (getChild [parent index] (get-child parent index))
                    (getChildCount [parent] (int (get-child-count parent)))
                    (getIndexOfChild [parent child]
                      (let [n (get-child-count parent)]
                        (loop [i 0]
                          (cond
                            (>= i n) (int -1)
                            (= child (get-child parent i)) (int i)
                            :else (recur (inc i))))))
                    (valueForPathChanged [_ _] nil)
                    (addTreeModelListener [_] nil)
                    (removeTreeModelListener [_] nil)))

                (defn list-model [provider]
                  (proxy [javax.swing.table.TableModel] []
                    (getRowCount [] (int (:nrows provider)))
                    (getColumnCount [] (int 2))
                    (getColumnName [col] (if (zero? col) "Key" "Value"))
                    (getColumnClass [_] Object)
                    (isCellEditable [_ _] false)
                    (getValueAt [row col]
                      (if (zero? col)
                        ((:get-label provider) row)
                        ((:get-value provider) row)))
                    (setValueAt [_ _ _] nil)
                    (addTableModelListener [_] nil)
                    (removeTableModelListener [_] nil)))

                (defn old-table-model [data]
                  (let [rows (vec data)
                        first-row (first rows)
                        cols (cond
                               (map? first-row) (vec (keys first-row))
                               (sequential? first-row) (vec (range (count first-row)))
                               :else [0])]
                    (proxy [javax.swing.table.TableModel] []
                      (getRowCount [] (int (count rows)))
                      (getColumnCount [] (int (count cols)))
                      (getColumnName [col] (str (nth cols col)))
                      (getColumnClass [_] Object)
                      (isCellEditable [_ _] false)
                      (getValueAt [row col]
                        (let [r (nth rows row)
                              c (nth cols col)]
                          (cond
                            (map? r) (get r c)
                            (sequential? r) (nth r c nil)
                            :else r)))
                      (setValueAt [_ _ _] nil)
                      (addTableModelListener [_] nil)
                      (removeTableModelListener [_] nil))))

                (defn inspect [x]
                  (when (__headless?)
                    (throw (java.awt.HeadlessException. "Cannot open inspector UI in headless mode")))
                  (let [frame (javax.swing.JFrame. "Clojure Inspector")
                        table (javax.swing.JTable. (list-model (list-provider x)))]
                    (.add (.getContentPane frame) (javax.swing.JScrollPane. table))
                    (.setSize frame 400 600)
                    (.setVisible frame true)
                    frame))

                (defn inspect-tree [data]
                  (when (__headless?)
                    (throw (java.awt.HeadlessException. "Cannot open inspector UI in headless mode")))
                  (let [frame (javax.swing.JFrame. "Clojure Inspector")
                        tree (javax.swing.JTree. (tree-model data))]
                    (.add (.getContentPane frame) (javax.swing.JScrollPane. tree))
                    (.setSize frame 400 600)
                    (.setVisible frame true)
                    frame))

                (defn inspect-table [data]
                  (when (__headless?)
                    (throw (java.awt.HeadlessException. "Cannot open inspector UI in headless mode")))
                  (let [frame (javax.swing.JFrame. "Clojure Inspector")
                        table (javax.swing.JTable. (old-table-model data))]
                    (.add (.getContentPane frame) (javax.swing.JScrollPane. table))
                    (.setSize frame 400 600)
                    (.setVisible frame true)
                    frame))
                """, "clojure/inspector.clj");
        } finally {
            Var.popThreadBindings();
        }
        Core.markLibLoaded("clojure.inspector");
    }
}
