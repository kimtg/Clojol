package clojure.lang;

import cljl.lang.PersistentList;

/**
 * Clean-room clojure.lang.RestFn base class for variadic compiled Clojure functions.
 */
public abstract class RestFn extends AFunction {

    public abstract int getRequiredArity();

    protected Object doInvoke(Object args) { return throwArity(0); }
    protected Object doInvoke(Object a1, Object args) { return throwArity(1); }
    protected Object doInvoke(Object a1, Object a2, Object args) { return throwArity(2); }
    protected Object doInvoke(Object a1, Object a2, Object a3, Object args) { return throwArity(3); }
    protected Object doInvoke(Object a1, Object a2, Object a3, Object a4, Object args) { return throwArity(4); }

    @Override
    public Object invokeVarargs(Object[] args) {
        int req = getRequiredArity();
        if (args.length < req) throw new ArityException(args.length, getClass().getSimpleName());
        ISeq rest = args.length > req ? PersistentList.createFromArraySlice(args, req) : null;
        return switch (req) {
            case 0 -> doInvoke(rest);
            case 1 -> doInvoke(args[0], rest);
            case 2 -> doInvoke(args[0], args[1], rest);
            case 3 -> doInvoke(args[0], args[1], args[2], rest);
            case 4 -> doInvoke(args[0], args[1], args[2], args[3], rest);
            default -> throw new ArityException(args.length, getClass().getSimpleName());
        };
    }

    @Override
    public Object applyTo(ISeq arglist) {
        return invokeVarargs(cljl.RT.toArray(arglist));
    }
}
