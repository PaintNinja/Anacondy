package ga.ozli.minecraftmods.anacondy;

import java.lang.invoke.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused") // called by ldc and invokedynamic
public final class AnacondyBootstraps {
    private AnacondyBootstraps() {}

    private static final ClassValue<Map<String, CallSite>> VALUES = new ClassValue<>() {
        @Override
        protected Map<String, CallSite> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>(4);
        }
    };
    private static final MethodHandle CHECKER_HANDLE;
    static {
        try {
            CHECKER_HANDLE = MethodHandles.lookup().findStatic(
                    AnacondyBootstraps.class,
                    "checkAndUpdateGrabber",
                    MethodType.methodType(Object.class, CallSite.class, MethodHandle.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Object invokeNonNull(
            MethodHandles.Lookup lookup, String name, Class<?> type, MethodHandle handle
    ) throws Throwable {
        return Objects.requireNonNull(handle.invoke(), name);
    }

    public static Object invokeNonNull(
            MethodHandles.Lookup lookup, String name, Class<?> type, MethodHandle handle, Object... args
    ) throws Throwable {
        return Objects.requireNonNull(handle.invokeWithArguments(args), name);
    }

    public static CallSite constantFoldWhenNonNull(
            MethodHandles.Lookup lookup, String name, MethodType methodType, Class<?> owner, MethodHandle fieldGetter
    ) throws Throwable {
        // Check if another `GETSTATIC` of the same field has already got a non-null value
        var grabber = VALUES.get(owner).get(name);
        if (grabber instanceof ConstantCallSite)
            return grabber; // already has, so share its constant call site with this one

        // Get the current value of the field. If it's non-null, we can make it eligible for constant folding
        var value = fieldGetter.invoke();
        if (value != null) {
            var constant = MethodHandles.constant(methodType.returnType(), value);
            if (grabber instanceof MutableCallSite mutableGrabber) {
                // If there's an existing getter, update it to the constant
                mutableGrabber.setTarget(constant);
            } else {
                // Otherwise, update the value map to use a ConstantCallSite for future accesses of this field
                grabber = new ConstantCallSite(constant);
                VALUES.get(owner).put(name, grabber);
            }

            return grabber;
        }

        // The field is still null, so make this field access intercepted by
        // AnacondyBootstraps#checkAndUpdateGrabber(CallSite, MethodHandle)
        if (grabber == null)
            grabber = new MutableCallSite(methodType);

        // Curry the grabber and fieldGetter into the checker
        var checker = MethodHandles.insertArguments(CHECKER_HANDLE, 0, grabber, fieldGetter).asType(methodType);

        grabber.setTarget(checker);
        VALUES.get(owner).put(name, grabber);
        return grabber;
    }

    /**
     * Intercepts a field get to check if the field is now non-null, and if so, stops intercepting and updates the
     * grabber to point to a constant value
     */
    private static Object checkAndUpdateGrabber(CallSite grabber, MethodHandle fieldGetter) throws Throwable {
        var value = fieldGetter.invoke();
        if (value != null)
            grabber.setTarget(MethodHandles.constant(grabber.type().returnType(), value));

        return value;
    }
}
