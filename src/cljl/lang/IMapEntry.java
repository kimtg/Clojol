package cljl.lang;

import java.util.Map;

/**
 * Clojure map entry interface (clojure.lang.IMapEntry).
 */
public interface IMapEntry extends clojure.lang.IMapEntry, Map.Entry<Object, Object> {
    @Override
    Object key();
    @Override
    Object val();
}
