package ga.ozli.minecraftmods.anacondytransformer;

import org.objectweb.asm.Handle;

import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Objects;

public final class AnacondyBootstraps {
    private AnacondyBootstraps() {}

    static final DirectMethodHandleDesc BSM_INVOKE_NON_NULL = ConstantDescs.ofConstantBootstrap(
            AnacondyBootstraps.class.describeConstable().orElseThrow(),
            "invokeNonNull",
            ConstantDescs.CD_Object,
            ConstantDescs.CD_MethodHandle
    );
    static final DirectMethodHandleDesc BSM_INVOKE_NON_NULL_WITH_ARGS = ConstantDescs.ofConstantBootstrap(
            BSM_INVOKE_NON_NULL.owner(),
            BSM_INVOKE_NON_NULL.methodName(),
            ConstantDescs.CD_Object,
            ConstantDescs.CD_MethodHandle,
            ConstantDescs.CD_Object.arrayType()
    );

    static final Handle HANDLE_BSM_INVOKE = Utils.toAsmHandle(ConstantDescs.BSM_INVOKE);
    static final Handle HANDLE_BSM_INVOKE_NON_NULL = Utils.toAsmHandle(BSM_INVOKE_NON_NULL);
    static final Handle HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS = Utils.toAsmHandle(BSM_INVOKE_NON_NULL_WITH_ARGS);

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
