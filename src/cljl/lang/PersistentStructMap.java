package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.io.Serializable;
import java.util.*;

public final class PersistentStructMap extends Fn
        implements IPersistentMap, IObj, IHashEq, IKVReduce, Map<Object, Object>, Iterable<Map.Entry<Object, Object>>, Serializable {

    private static final long serialVersionUID = 1L;

    public static final class Def implements Serializable {
        private static final long serialVersionUID = 1L;
        public final ISeq keys;
        public final Map<RT.EquivKey, Integer> keyMap;
        public final Object[] keyArray;

        Def(ISeq keys, Map<RT.EquivKey, Integer> keyMap, Object[] keyArray) {
            this.keys = keys;
            this.keyMap = keyMap;
            this.keyArray = keyArray;
        }
    }

    public final Def def;
    public final Object[] vals;
    public final PersistentHashMap ext;
    private final PersistentHashMap meta;

    private PersistentStructMap(PersistentHashMap meta, Def def, Object[] vals, PersistentHashMap ext) {
        this.meta = meta;
        this.def = def;
        this.vals = vals;
        this.ext = ext != null ? ext : PersistentHashMap.EMPTY;
    }

    public static Def createSlotMap(ISeq keys) {
        List<Object> list = new ArrayList<>();
        Map<RT.EquivKey, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        for (ISeq s = RT.seq(keys); s != null; s = s.next()) {
            Object k = s.first();
            RT.EquivKey ek = new RT.EquivKey(k);
            if (!map.containsKey(ek)) {
                map.put(ek, idx++);
                list.add(k);
            }
        }
        return new Def(PersistentList.create(list), map, list.toArray());
    }

    public static PersistentStructMap create(Def def, ISeq keyvals) {
        Object[] vals = new Object[def.keyArray.length];
        PersistentHashMap ext = PersistentHashMap.EMPTY;
        for (ISeq s = RT.seq(keyvals); s != null; s = s.next()) {
            Object k = s.first();
            ISeq n = s.next();
            if (n == null) throw new IllegalArgumentException("No value supplied for key: " + k);
            Object v = n.first();
            Integer idx = def.keyMap.get(new RT.EquivKey(k));
            if (idx != null) {
                vals[idx] = v;
            } else {
                ext = ext.assoc(k, v);
            }
            s = n;
        }
        return new PersistentStructMap(null, def, vals, ext);
    }

    public static PersistentStructMap construct(Def def, ISeq valsSeq) {
        Object[] vals = new Object[def.keyArray.length];
        int i = 0;
        for (ISeq s = RT.seq(valsSeq); s != null; s = s.next()) {
            if (i >= vals.length) {
                throw new IllegalArgumentException("Too many arguments to struct constructor");
            }
            vals[i++] = s.first();
        }
        return new PersistentStructMap(null, def, vals, PersistentHashMap.EMPTY);
    }

    public static Fn accessor(Def def, Object key) {
        Integer idx = def.keyMap.get(new RT.EquivKey(key));
        if (idx == null) {
            throw new IllegalArgumentException("Struct does not contain key: " + key);
        }
        final int slot = idx;
        return Fn.of1("accessor", obj -> {
            if (obj instanceof PersistentStructMap sm && sm.def == def) {
                return sm.vals[slot];
            }
            return RT.get(obj, key);
        });
    }

    @Override public PersistentHashMap meta() { return meta; }

    @Override
    public PersistentStructMap withMeta(PersistentHashMap m) {
        return this.meta == m ? this : new PersistentStructMap(m, def, vals, ext);
    }

    @Override
    public boolean containsKey(Object key) {
        return def.keyMap.containsKey(new RT.EquivKey(key)) || ext.containsKey(key);
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        Integer idx = def.keyMap.get(new RT.EquivKey(key));
        if (idx != null) return vals[idx];
        return ext.valAt(key, notFound);
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        Integer idx = def.keyMap.get(new RT.EquivKey(key));
        if (idx != null) {
            if (RT.equiv(vals[idx], val)) return this;
            Object[] next = vals.clone();
            next[idx] = val;
            return new PersistentStructMap(meta, def, next, ext);
        }
        return new PersistentStructMap(meta, def, vals, ext.assoc(key, val));
    }

    @Override
    public PersistentStructMap without(Object key) {
        if (def.keyMap.containsKey(new RT.EquivKey(key))) {
            throw new IllegalArgumentException("Can't remove struct key: " + key);
        }
        PersistentHashMap newExt = ext.without(key);
        return newExt == ext ? this : new PersistentStructMap(meta, def, vals, newExt);
    }

    @Override
    public IPersistentCollection cons(Object o) {
        if (o == null) return this;
        if (o instanceof Map.Entry<?, ?> e) {
            return assoc(e.getKey(), e.getValue());
        }
        if (o instanceof IPersistentVector v) {
            if (v.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
            return assoc(v.nth(0), v.nth(1));
        }
        IPersistentMap ret = this;
        for (ISeq s = RT.seq(o); s != null; s = s.next()) {
            Object item = s.first();
            if (item instanceof Map.Entry<?, ?> e) {
                ret = ret.assoc(e.getKey(), e.getValue());
            } else {
                throw new IllegalArgumentException("Can't conj " + item + " to struct map");
            }
        }
        return ret;
    }

    @Override
    public IPersistentCollection empty() {
        return construct(def, null).withMeta(meta);
    }

    @Override
    public Object kvreduce(Fn f, Object init) {
        Object acc = init;
        for (int i = 0; i < vals.length; i++) {
            acc = f.invoke(acc, def.keyArray[i], vals[i]);
            if (acc instanceof Reduced r) return r.deref();
        }
        return ext.kvreduce(f, acc);
    }

    @Override public Object invoke(Object key) { return valAt(key, null); }
    @Override public Object invoke(Object key, Object notFound) { return valAt(key, notFound); }
    @Override public int count() { return vals.length + ext.count(); }

    @Override
    public ISeq seq() {
        List<Object> entries = new ArrayList<>(count());
        for (int i = 0; i < vals.length; i++) {
            entries.add(new PersistentHashMap.MapEntry(def.keyArray[i], vals[i]));
        }
        for (ISeq s = ext.seq(); s != null; s = s.next()) {
            entries.add(s.first());
        }
        return entries.isEmpty() ? null : PersistentList.create(entries);
    }

    @Override public int size() { return count(); }
    @Override public boolean isEmpty() { return count() == 0; }
    @Override public boolean containsValue(Object value) {
        for (Object v : vals) if (RT.equiv(v, value)) return true;
        return ext.containsValue(value);
    }
    @Override public Object get(Object key) { return valAt(key); }
    @Override public Object put(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    @Override
    public Set<Object> keySet() {
        Set<Object> set = new LinkedHashSet<>(count());
        Collections.addAll(set, def.keyArray);
        set.addAll(ext.keySet());
        return Collections.unmodifiableSet(set);
    }

    @Override
    public Collection<Object> values() {
        List<Object> list = new ArrayList<>(count());
        Collections.addAll(list, vals);
        list.addAll(ext.values());
        return Collections.unmodifiableList(list);
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
        Set<Entry<Object, Object>> set = new LinkedHashSet<>(count());
        for (int i = 0; i < vals.length; i++) {
            set.add(new PersistentHashMap.MapEntry(def.keyArray[i], vals[i]));
        }
        set.addAll(ext.entrySet());
        return Collections.unmodifiableSet(set);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Iterator<Entry<Object, Object>> iterator() {
        ISeq s = seq();
        return s == null ? Collections.emptyIterator() : (Iterator) new ASeq.SeqIterator(s);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Map<?, ?> m)) return false;
        if (count() != m.size()) return false;
        for (Entry<Object, Object> e : this) {
            Object k = e.getKey();
            if (!m.containsKey(k) || !RT.equiv(e.getValue(), m.get(k))) return false;
        }
        return true;
    }

    @Override
    public int hasheq() {
        int sum = 0;
        int n = 0;
        for (Entry<Object, Object> e : this) {
            sum += Murmur3.mixCollHash(31 * (31 + RT.hash(e.getKey())) + RT.hash(e.getValue()), 2);
            n++;
        }
        return Murmur3.mixCollHash(sum, n);
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Entry<Object, Object> e : this) {
            h += (RT.hash(e.getKey()) ^ RT.hash(e.getValue()));
        }
        return h;
    }

    @Override
    public String toString() {
        return RT.printString(this);
    }
}
