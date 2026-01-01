package ga.ozli.minecraftmods.anacondy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Objects;

public final class AnacondyBootstraps {
    private AnacondyBootstraps() {}

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
}
