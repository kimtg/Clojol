package cljl.lang;

/**
 * Editable persistent collection supporting asTransient() (Clojure clojure.lang.IEditableCollection).
 */
public interface IEditableCollection {
    Object asTransient();
}
