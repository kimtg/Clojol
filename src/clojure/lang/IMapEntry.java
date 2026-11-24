package clojure.lang;

import java.util.Map;

public interface IMapEntry extends Map.Entry<Object, Object> {
    Object key();
    Object val();
}
