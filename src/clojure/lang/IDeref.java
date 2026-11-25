package clojure.lang;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public interface IDeref extends Supplier<Object>, BooleanSupplier, IntSupplier, LongSupplier, DoubleSupplier {
    Object deref();

    @Override
    default Object get() {
        return deref();
    }

    @Override
    default boolean getAsBoolean() {
        return cljl.RT.truthy(deref());
    }

    @Override
    default int getAsInt() {
        return cljl.Numbers.intCast(deref());
    }

    @Override
    default long getAsLong() {
        return cljl.Numbers.longCast(deref());
    }

    @Override
    default double getAsDouble() {
        return cljl.Numbers.toDouble(deref());
    }
}
