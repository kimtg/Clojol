package cljl;

import java.util.Objects;

/**
 * Compact lattice of inferred and hinted types for ClojureLite's compiler and BytecodeJIT.
 */
public final class ValType {

    public enum Kind {
        BOTTOM,
        NIL,
        LONG,
        DOUBLE,
        BOOL,
        REF,
        ANY
    }

    public static final ValType BOTTOM = new ValType(Kind.BOTTOM, null, false);
    public static final ValType NIL = new ValType(Kind.NIL, null, false);
    public static final ValType LONG = new ValType(Kind.LONG, Long.class, true);
    public static final ValType DOUBLE = new ValType(Kind.DOUBLE, Double.class, true);
    public static final ValType BOOL = new ValType(Kind.BOOL, Boolean.class, true);
    public static final ValType ANY = new ValType(Kind.ANY, Object.class, false);
    public static final ValType NUMBER = new ValType(Kind.REF, Number.class, true);

    public final Kind kind;
    public final Class<?> refClass;
    public final boolean nonNull;

    private ValType(Kind kind, Class<?> refClass, boolean nonNull) {
        this.kind = kind;
        this.refClass = refClass;
        this.nonNull = nonNull;
    }

    public static ValType ref(Class<?> cls, boolean nonNull) {
        if (cls == null || cls == Object.class) return ANY;
        if (cls == long.class || cls == Long.class) return LONG;
        if (cls == double.class || cls == Double.class) return DOUBLE;
        if (cls == boolean.class || cls == Boolean.class) return BOOL;
        if (cls == void.class || cls == Void.class) return NIL;
        if (cls == Number.class && nonNull) return NUMBER;
        return new ValType(Kind.REF, cls, nonNull);
    }

    public static ValType fromJavaReturn(Class<?> retType) {
        if (retType == void.class) return NIL;
        if (retType == long.class || retType == int.class || retType == short.class || retType == byte.class) {
            return LONG;
        }
        if (retType == double.class || retType == float.class) {
            return DOUBLE;
        }
        if (retType == boolean.class) {
            return BOOL;
        }
        if (retType == Object.class) return ANY;
        return new ValType(Kind.REF, retType, false);
    }

    public static ValType fromConstant(Object val) {
        if (val == null) return NIL;
        if (val instanceof Long) return LONG;
        if (val instanceof Double) return DOUBLE;
        if (val instanceof Boolean) return BOOL;
        return new ValType(Kind.REF, val.getClass(), true);
    }

    public static ValType fromTag(Object tag, Namespace ns) {
        if (tag == null) return ANY;
        if (tag instanceof Class<?> c) {
            return ref(c, false);
        }
        String name;
        if (tag instanceof Symbol sym) {
            name = sym.ns == null ? sym.name : (sym.ns + "." + sym.name);
        } else if (tag instanceof String s) {
            name = s;
        } else {
            return ANY;
        }
        switch (name) {
            case "long", "Long", "java.lang.Long" -> { return LONG; }
            case "double", "Double", "java.lang.Double" -> { return DOUBLE; }
            case "boolean", "Boolean", "java.lang.Boolean" -> { return BOOL; }
        }
        Class<?> cls = Interop.resolveClass(ns, name);
        if (cls != null && !cls.isPrimitive()) {
            return ref(cls, false);
        }
        return ANY;
    }

    public boolean isPrimitive() {
        return kind == Kind.LONG || kind == Kind.DOUBLE || kind == Kind.BOOL;
    }

    public boolean isNumeric() {
        return kind == Kind.LONG || kind == Kind.DOUBLE;
    }

    public boolean isLong() {
        return kind == Kind.LONG;
    }

    public boolean isDouble() {
        return kind == Kind.DOUBLE;
    }

    public boolean isBool() {
        return kind == Kind.BOOL;
    }

    public boolean isRef() {
        return kind == Kind.REF && refClass != null && refClass != Object.class;
    }

    public Class<?> boxClass() {
        return switch (kind) {
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
            case BOOL -> Boolean.class;
            case REF -> refClass;
            default -> Object.class;
        };
    }

    public char sigChar() {
        return switch (kind) {
            case LONG -> 'J';
            case DOUBLE -> 'D';
            case BOOL -> 'Z';
            default -> 'O';
        };
    }

    public String internalName() {
        if (refClass == null || refClass == Object.class) return "java/lang/Object";
        return refClass.getName().replace('.', '/');
    }

    public static ValType join(ValType a, ValType b) {
        if (a == null || b == null) return ANY;
        if (a.kind == Kind.BOTTOM) return b;
        if (b.kind == Kind.BOTTOM) return a;
        if (a.equals(b)) return a;
        if (a.kind == Kind.ANY || b.kind == Kind.ANY) return ANY;

        if (a.kind == Kind.NIL) {
            if (b.kind == Kind.NIL) return NIL;
            if (b.kind == Kind.REF) return ref(b.refClass, false);
            return ANY;
        }
        if (b.kind == Kind.NIL) {
            if (a.kind == Kind.REF) return ref(a.refClass, false);
            return ANY;
        }

        if (a.isNumeric() && b.isNumeric()) {
            return NUMBER;
        }

        if (a.isNumeric() && b.kind == Kind.REF && Number.class.isAssignableFrom(b.refClass)) {
            return b.nonNull ? NUMBER : ref(Number.class, false);
        }
        if (b.isNumeric() && a.kind == Kind.REF && Number.class.isAssignableFrom(a.refClass)) {
            return a.nonNull ? NUMBER : ref(Number.class, false);
        }

        if (a.kind == Kind.REF && b.kind == Kind.REF) {
            Class<?> ca = a.refClass;
            Class<?> cb = b.refClass;
            boolean nn = a.nonNull && b.nonNull;
            if (ca.isAssignableFrom(cb)) return ref(ca, nn);
            if (cb.isAssignableFrom(ca)) return ref(cb, nn);
            Class<?> sup = ca.getSuperclass();
            while (sup != null && sup != Object.class) {
                if (sup.isAssignableFrom(cb)) return ref(sup, nn);
                sup = sup.getSuperclass();
            }
            return ANY;
        }

        return ANY;
    }

    public static ValType narrow(ValType inferred, ValType hint) {
        if (inferred == BOTTOM) return BOTTOM;
        if (hint == null || hint == ANY || hint == BOTTOM) return inferred;
        if (hint.isPrimitive()) {
            return hint;
        }
        if (hint.kind == Kind.REF) {
            if (inferred.kind == Kind.REF && hint.refClass.isAssignableFrom(inferred.refClass)) {
                return inferred;
            }
            return ref(hint.refClass, inferred.nonNull);
        }
        return inferred;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValType other)) return false;
        return kind == other.kind && nonNull == other.nonNull && Objects.equals(refClass, other.refClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, refClass, nonNull);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case BOTTOM -> "BOTTOM";
            case NIL -> "NIL";
            case LONG -> "long";
            case DOUBLE -> "double";
            case BOOL -> "boolean";
            case ANY -> "Object";
            case REF -> refClass.getSimpleName() + (nonNull ? "!" : "?");
        };
    }
}
