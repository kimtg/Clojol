package cljl.lang;

import java.util.Map;

/**
 * Clojure map entry interface (clojure.lang.IMapEntry).
 */
public interface IMapEntry extends Map.Entry<Object, Object> {
    Object key();
    Object val();
}
