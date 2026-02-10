package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import ga.ozli.minecraftmods.anacondy.AnacondyBootstraps;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;
import static cpw.mods.modlauncher.api.ITransformer.Target.targetMethod;

public final class AnacondyTransformers {
    private AnacondyTransformers() {}

    static final Logger LOGGER = LoggerFactory.getLogger(AnacondyTransformers.class);

    static final ITransformer.Target TARGET_MC_GET_INSTANCE_METHOD = ITransformer.Target.targetMethod(
            Utils.MINECRAFT_CLASS_NAME,
            "getInstance",
            "()Lnet/minecraft/client/Minecraft;"
    );

    static final Handle HANDLE_MC_INSTANCE_FIELD = new Handle(
            Opcodes.H_GETSTATIC,
            Utils.MINECRAFT_CLASS_NAME,
            "instance",
            "Lnet/minecraft/client/Minecraft;",
            false
    );

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

    private static final ConstantDynamic CONDY_MC_INSTANCE_FIELD = new ConstantDynamic(
            "MINECRAFT_INSTANCE",
            HANDLE_MC_INSTANCE_FIELD.getDesc(),
            AnacondyTransformers.HANDLE_BSM_INVOKE_NON_NULL,
            HANDLE_MC_INSTANCE_FIELD
    );
    private static final ConstantDynamic CONDY_MC_GET_INSTANCE = new ConstantDynamic(
            CONDY_MC_INSTANCE_FIELD.getName(),
            HANDLE_MC_INSTANCE_FIELD.getDesc(),
            AnacondyTransformers.HANDLE_BSM_INVOKE_NON_NULL,
            new Handle(
                    Opcodes.H_INVOKESTATIC,
                    Utils.MINECRAFT_CLASS_NAME,
                    TARGET_MC_GET_INSTANCE_METHOD.elementName(),
                    TARGET_MC_GET_INSTANCE_METHOD.elementDescriptor(),
                    false
            )
    );

    /** Fields in net/minecraft/client/Minecraft that are assumed to always be public final fields **/
    private static final Set<String> TRUSTED_MC_FIELDS = Set.of(
            "options", "levelRenderer", "particleEngine", "gameRenderer", "mouseHandler", "keyboardHandler", "font",
            "gui", "debugEntries"
    );

    static final AtomicInteger TOTAL_REWRITES = new AtomicInteger(0);

    @SuppressWarnings("rawtypes")
    public static List<ITransformer> getAll() {
        LOGGER.info("Anacondy started");
        return List.of(
                // Rewrite `GETSTATIC Minecraft.instance` inside `Minecraft.getInstance()` to use LDC ConstantDynamic
                new StaticFieldGetToCondy(
                        TARGET_MC_GET_INSTANCE_METHOD,
                        "instance",
                        "MINECRAFT_INSTANCE"
                ),

                new StaticFieldGetToCondy(
                        targetMethod(
                                "net/minecraft/SharedConstants",
                                "getCurrentVersion",
                                "()Lnet/minecraft/WorldVersion;"
                        ),
                        "CURRENT_VERSION",
                        "CURRENT_VERSION_INSTANCE"
                ),

                new StaticFieldGetToIndy.ConstantOnceNonNull(
                        targetMethod(
                                "com/mojang/blaze3d/platform/GLX",
                                "_getCpuInfo",
                                "()Ljava/lang/String;"
                        ),
                        "cpuInfo"
                ),

                new DebugEntrySystemSpecsTransformer(),

                new SingletonAccessedForeignFieldsTransformer(
                        Set.of(
                                // some of these are commented out due to deadlock issues during startup - needs further investigation
                                targetClass("com/mojang/blaze3d/systems/RenderSystem"),

//                                targetClass("net/minecraft/client/MouseHandler"),

//                                targetClass("net/minecraft/client/gui/components/DebugScreenOverlay"),

                                targetClass("net/minecraft/client/gui/components/debug/DebugEntryFps"),
                                targetClass("net/minecraft/client/gui/components/debug/DebugEntryEntityRenderStats"),
                                targetClass("net/minecraft/client/gui/components/debug/DebugEntryParticleRenderStats"),
                                targetClass("net/minecraft/client/gui/components/debug/DebugEntryPostEffect"),
                                targetClass("net/minecraft/client/gui/components/debug/DebugEntrySimplePerformanceImpactors"),
//
                                targetClass("net/minecraft/client/gui/render/GuiRenderer"),

//                                targetClass("net/minecraft/client/multiplayer/ClientLevel"),

                                targetClass("net/minecraft/client/renderer/CloudRenderer"),
//                                targetClass("net/minecraft/client/renderer/GameRenderer")
//                                targetClass("net/minecraft/client/renderer/LevelRenderer"),
//                                targetClass("net/minecraft/client/renderer/LightTexture"),
//                                targetClass("net/minecraft/client/renderer/ItemInHandRenderer"),

                                targetClass("net/minecraft/client/renderer/debug/DebugRenderer")

//                                targetClass("net/minecraft/client/renderer/feature/TextFeatureRenderer")
                        ),
                        CONDY_MC_GET_INSTANCE,
                        TRUSTED_MC_FIELDS
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass(Utils.MINECRAFT_CLASS_NAME),
                        CONDY_MC_INSTANCE_FIELD,
                        Set.of(
                                // only called once during startup
                                ConstantDescs.CLASS_INIT_NAME, ConstantDescs.INIT_NAME,

                                // only called once during shutdown
                                "close", "destroy", "emergencySave", "emergencySaveAndCrash"
                        )
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/Options"),
                        new ConstantDynamic(
                                "OPTIONS_INSTANCE",
                                "Lnet/minecraft/client/Options;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        Utils.MINECRAFT_CLASS_NAME,
                                        "options",
                                        "Lnet/minecraft/client/Options;",
                                        false
                                ),
                                CONDY_MC_GET_INSTANCE
                        ),
                        Set.of(
                                // only called once during startup
                                ConstantDescs.CLASS_INIT_NAME, ConstantDescs.INIT_NAME,
                                "setForgeKeybindProperties", "getFile", "load",

                                // breaks startup if constant folded
                                "processOptions",

                                // just in case
                                "processDumpedOptions", "dumpOptionsForReport", "processOptionsEnd", "save"
                        )
                ),

                new ClassToRecordTransformer(),

                //region Rendering
                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        new ConstantDynamic(
                                "GAME_RENDERER_INSTANCE",
                                "Lnet/minecraft/client/renderer/GameRenderer;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        Utils.MINECRAFT_CLASS_NAME,
                                        "gameRenderer",
                                        "Lnet/minecraft/client/renderer/GameRenderer;",
                                        false
                                ),
                                CONDY_MC_GET_INSTANCE
                        )
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/LevelRenderer"),
                        new ConstantDynamic(
                                "LEVEL_RENDERER_INSTANCE",
                                "Lnet/minecraft/client/renderer/LevelRenderer;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        Utils.MINECRAFT_CLASS_NAME,
                                        "levelRenderer",
                                        "Lnet/minecraft/client/renderer/LevelRenderer;",
                                        false
                                ),
                                CONDY_MC_GET_INSTANCE
                        )
                ),

                // todo: reconsider condy on DebugRenderer - only really applies to grabbing the renderers field for the
                //       iterator inside the emitGizmos method - probably not worth it
                // net/minecraft/client/renderer/debug/DebugRenderer
                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/debug/DebugRenderer"),
                        new ConstantDynamic(
                                "DEBUG_RENDERER_INSTANCE",
                                "Lnet/minecraft/client/renderer/debug/DebugRenderer;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        "net/minecraft/client/renderer/LevelRenderer",
                                        "debugRenderer",
                                        "Lnet/minecraft/client/renderer/debug/DebugRenderer;",
                                        false
                                ),
                                new ConstantDynamic(
                                        "LEVEL_RENDERER_INSTANCE",
                                        "Lnet/minecraft/client/renderer/LevelRenderer;",
                                        HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                        new Handle(
                                                Opcodes.H_GETFIELD,
                                                Utils.MINECRAFT_CLASS_NAME,
                                                "levelRenderer",
                                                "Lnet/minecraft/client/renderer/LevelRenderer;",
                                                false
                                        ),
                                        CONDY_MC_GET_INSTANCE
                                )
                        ),
                        // whitelist only emitGizmos
                        methodName -> !methodName.equals("emitGizmos")
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/RenderBuffers"),
                        new ConstantDynamic(
                                "RENDER_BUFFERS_INSTANCE",
                                "Lnet/minecraft/client/renderer/RenderBuffers;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        Utils.MINECRAFT_CLASS_NAME,
                                        "renderBuffers",
                                        "Lnet/minecraft/client/renderer/RenderBuffers;",
                                        false
                                ),
                                CONDY_MC_GET_INSTANCE
                        )
                ),

                // because renderBuffers is private in both LevelRenderer and Minecraft, which prevents CONDY from
                // resolving due to the lookup class being sourced from RenderBuffers, so we need to make it accessible first
                new Workarounds.MakeFieldAccessible(
                        targetClass("net/minecraft/client/Minecraft"),
                        "renderBuffers",
                        "Lnet/minecraft/client/renderer/RenderBuffers;"
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/feature/FeatureRenderDispatcher"),
                        new ConstantDynamic(
                                "FEATURE_RENDER_DISPATCHER_INSTANCE",
                                "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        "net/minecraft/client/renderer/GameRenderer",
                                        "featureRenderDispatcher",
                                        "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;",
                                        false
                                ),
                                new ConstantDynamic(
                                        "GAME_RENDERER_INSTANCE",
                                        "Lnet/minecraft/client/renderer/GameRenderer;",
                                        HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                        new Handle(
                                                Opcodes.H_GETFIELD,
                                                Utils.MINECRAFT_CLASS_NAME,
                                                "gameRenderer",
                                                "Lnet/minecraft/client/renderer/GameRenderer;",
                                                false
                                        ),
                                        CONDY_MC_GET_INSTANCE
                                )
                        )
                ),

                new Workarounds.MakeFieldAccessible(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        "featureRenderDispatcher",
                        "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;"
                ),
                //endregion

                new Workarounds.MinecraftClientAddGetWindowNoInlineMethodTransformer(),
                new Workarounds.OptionsGetFullscreenVideoModeStringFixer(),

                // Rewrite `GETFIELD minecraft` copies in various classes to call `Minecraft.getInstance()` instead,
                // to benefit from the CONDY optimisation there
                new MinecraftClientFieldCopiesTransformer(),

                // `GETSTATIC Tesselator.instance` inside `Tesselator.getInstance()`
                new StaticFieldGetToCondy(
                        targetMethod(
                                "com/mojang/blaze3d/vertex/Tesselator",
                                "getInstance",
                                "()Lcom/mojang/blaze3d/vertex/Tesselator;"
                        ),
                        "instance",
                        "TESSELATOR_INSTANCE"
                ),

                // todo: jtracy is not on the game layer so can't be transformed :(
//                new StaticFieldGetToIndy.MostlyConstant(
//                        targetClass("com/mojang/jtracy/TracyClient"),
//                        "loaded"
//                ),

                //region RenderSystem
                // `GETSTATIC RenderSystem.renderThread` inside `RenderSystem.isOnRenderThread()`
                new StaticFieldGetToCondy(
                        targetMethod(
                                "com/mojang/blaze3d/systems/RenderSystem",
                                "isOnRenderThread",
                                "()Z"
                        ),
                        "renderThread"
                ),

                // `GETSTATIC RenderSystem.DEVICE` inside `RenderSystem.getDevice()`
                new StaticFieldGetToIndy.ConstantOnceNonNull(
                        targetMethod(
                                "com/mojang/blaze3d/systems/RenderSystem",
                                "getDevice",
                                "()Lcom/mojang/blaze3d/systems/GpuDevice;"
                        ),
                        "DEVICE"
                ),

                // `GETSTATIC RenderSystem.dynamicUniforms` inside `RenderSystem.getDynamicUniforms()`
                new StaticFieldGetToCondy(targetMethod(
                        "com/mojang/blaze3d/systems/RenderSystem",
                        "getDynamicUniforms",
                        "()Lnet/minecraft/client/renderer/DynamicUniforms;"
                )),

                // `GETSTATIC RenderSystem.dynamicUniforms` inside `RenderSystem.flipFrame(Window, TracyFrameCapture)`
                new StaticFieldGetToCondy(
                        targetMethod(
                                "com/mojang/blaze3d/systems/RenderSystem",
                                "flipFrame",
                                "(Lcom/mojang/blaze3d/platform/Window;Lcom/mojang/blaze3d/tracy/TracyFrameCapture;)V"
                        ),
                        "dynamicUniforms"
                ),

                // `GETSTATIC RenderSystem.samplerCache` inside `RenderSystem.getSamplerCache()`
                new StaticFieldGetToCondy(targetMethod(
                        "com/mojang/blaze3d/systems/RenderSystem",
                        "getSamplerCache",
                        "()Lcom/mojang/blaze3d/systems/SamplerCache;"
                )),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/Camera"),
                        new ConstantDynamic(
                                "CAMERA_INSTANCE",
                                "Lnet/minecraft/client/Camera;",
                                HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                new Handle(
                                        Opcodes.H_GETFIELD,
                                        "net/minecraft/client/renderer/GameRenderer",
                                        "mainCamera",
                                        "Lnet/minecraft/client/Camera;",
                                        false
                                ),
                                new ConstantDynamic(
                                        "GAME_RENDERER_INSTANCE",
                                        "Lnet/minecraft/client/renderer/GameRenderer;",
                                        HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                                        new Handle(
                                                Opcodes.H_GETFIELD,
                                                Utils.MINECRAFT_CLASS_NAME,
                                                "gameRenderer",
                                                "Lnet/minecraft/client/renderer/GameRenderer;",
                                                false
                                        ),
                                        CONDY_MC_GET_INSTANCE
                                )
                        )
                ),
                new Workarounds.MakeFieldAccessible(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        "mainCamera",
                        "Lnet/minecraft/client/Camera;"
                ),

                // Lighting done inside ClassToRecordTransformer instead
                //endregion

                //region Forge
                new StaticFieldGetToCondy(
                        targetMethod(
                                "net/minecraftforge/client/EntitySpectatorShaderManager",
                                "get",
                                "(Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/resources/Identifier;"
                                ),
                        "SHADERS",
                        "SHADERS"
                )
                //endregion
        );
    }
}
