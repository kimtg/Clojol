package cljl.lang;

/**
 * Editable persistent collection supporting asTransient() (Clojure clojure.lang.IEditableCollection).
 */
public interface IEditableCollection extends clojure.lang.IEditableCollection {
    @Override
    ITransientCollection asTransient();
}
