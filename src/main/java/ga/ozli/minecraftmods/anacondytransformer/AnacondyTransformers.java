package ga.ozli.minecraftmods.anacondytransformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.minecraftforge.coremod.api.ASMAPI;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.TypeDescriptor;
import java.lang.runtime.ObjectMethods;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;
import static cpw.mods.modlauncher.api.ITransformer.Target.targetMethod;

final class AnacondyTransformers {
    private AnacondyTransformers() {}

    private static final boolean NULL_CHECK_ON_CONDY_RESOLVE = true;
    private static final Logger LOGGER = LoggerFactory.getLogger(AnacondyTransformers.class);

    private static final ITransformer.Target TARGET_MC_GET_INSTANCE_METHOD = ITransformer.Target.targetMethod(
            Utils.MINECRAFT_CLASS_NAME,
            "getInstance",
            "()Lnet/minecraft/client/Minecraft;"
    );

    private static final Handle HANDLE_MC_INSTANCE_FIELD = new Handle(
            Opcodes.H_GETSTATIC,
            Utils.MINECRAFT_CLASS_NAME,
            "instance",
            "Lnet/minecraft/client/Minecraft;",
            false
    );

    private static final ConstantDynamic CONDY_MC_INSTANCE_FIELD = new ConstantDynamic(
            "MINECRAFT_INSTANCE",
            HANDLE_MC_INSTANCE_FIELD.getDesc(),
            NULL_CHECK_ON_CONDY_RESOLVE
                    ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL
                    : AnacondyBootstraps.HANDLE_BSM_INVOKE,
            HANDLE_MC_INSTANCE_FIELD
    );
    private static final ConstantDynamic CONDY_MC_GET_INSTANCE = new ConstantDynamic(
            CONDY_MC_INSTANCE_FIELD.getName(),
            HANDLE_MC_INSTANCE_FIELD.getDesc(),
            NULL_CHECK_ON_CONDY_RESOLVE
                    ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL
                    : AnacondyBootstraps.HANDLE_BSM_INVOKE,
            new Handle(
                    Opcodes.H_INVOKESTATIC,
                    Utils.MINECRAFT_CLASS_NAME,
                    TARGET_MC_GET_INSTANCE_METHOD.elementName(),
                    TARGET_MC_GET_INSTANCE_METHOD.elementDescriptor(),
                    false
            )
    );

    private static final AtomicInteger TOTAL_REWRITES = new AtomicInteger(0);

    interface Transformer<T> extends ITransformer<T> {
        @Override
        default @NotNull TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }
    }

    @SuppressWarnings("rawtypes")
    static List<ITransformer> getAll() {
        LOGGER.info("Anacondy started");
        return List.of(
                // Rewrite `GETSTATIC Minecraft.instance` inside `Minecraft.getInstance()` to use LDC ConstantDynamic
                new StaticFieldGetToCondyTransformer(
                        TARGET_MC_GET_INSTANCE_METHOD,
                        "instance",
                        "MINECRAFT_INSTANCE"
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
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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

                //region Rendering
                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        new ConstantDynamic(
                                "GAME_RENDERER_INSTANCE",
                                "Lnet/minecraft/client/renderer/GameRenderer;",
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                        NULL_CHECK_ON_CONDY_RESOLVE
                                                ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                                : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                new AnacondyWorkaroundTransformers.MakeFieldAccessibleTransformer(
                        targetClass("net/minecraft/client/Minecraft"),
                        "renderBuffers",
                        "Lnet/minecraft/client/renderer/RenderBuffers;"
                ),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/renderer/feature/FeatureRenderDispatcher"),
                        new ConstantDynamic(
                                "FEATURE_RENDER_DISPATCHER_INSTANCE",
                                "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;",
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                        NULL_CHECK_ON_CONDY_RESOLVE
                                                ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                                : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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

                new AnacondyWorkaroundTransformers.MakeFieldAccessibleTransformer(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        "featureRenderDispatcher",
                        "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;"
                ),
                //endregion

                new AnacondyWorkaroundTransformers.MinecraftClientAddGetWindowNoInlineMethodTransformer(),
                new AnacondyWorkaroundTransformers.OptionsGetFullscreenVideoModeStringFixer(),

                // Rewrite `GETFIELD minecraft` copies in various classes to call `Minecraft.getInstance()` instead,
                // to benefit from the CONDY optimisation there
                new MinecraftClientFieldCopiesTransformer(),

                // `GETSTATIC Tesselator.instance` inside `Tesselator.getInstance()`
                new StaticFieldGetToCondyTransformer(
                        targetMethod(
                                "com/mojang/blaze3d/vertex/Tesselator",
                                "getInstance",
                                "()Lcom/mojang/blaze3d/vertex/Tesselator;"
                        ),
                        "instance",
                        "TESSELATOR_INSTANCE"
                ),

                //region RenderSystem
                // `GETSTATIC RenderSystem.renderThread` inside `RenderSystem.isOnRenderThread()`
                new StaticFieldGetToCondyTransformer(
                        targetMethod(
                                "com/mojang/blaze3d/systems/RenderSystem",
                                "isOnRenderThread",
                                "()Z"
                        ),
                        "renderThread"
                ),

                // Todo: If the game crashes early then Anacondy gets blamed for the crash due to constant folding null.
                //       Look into INDY for this instead.
                // `GETSTATIC RenderSystem.DEVICE` inside `RenderSystem.getDevice()`
//                new StaticFieldGetToCondyTransformer(
//                        targetMethod(
//                                "com/mojang/blaze3d/systems/RenderSystem",
//                                "getDevice",
//                                "()Lcom/mojang/blaze3d/systems/GpuDevice;"
//                        ),
//                        "DEVICE",
//                        "DEVICE"
//                ),

                // `GETSTATIC RenderSystem.dynamicUniforms` inside `RenderSystem.getDynamicUniforms()`
                new StaticFieldGetToCondyTransformer(targetMethod(
                        "com/mojang/blaze3d/systems/RenderSystem",
                        "getDynamicUniforms",
                        "()Lnet/minecraft/client/renderer/DynamicUniforms;"
                )),

                // `GETSTATIC RenderSystem.dynamicUniforms` inside `RenderSystem.flipFrame(Window, TracyFrameCapture)`
                new StaticFieldGetToCondyTransformer(
                        targetMethod(
                                "com/mojang/blaze3d/systems/RenderSystem",
                                "flipFrame",
                                "(Lcom/mojang/blaze3d/platform/Window;Lcom/mojang/blaze3d/tracy/TracyFrameCapture;)V"
                        ),
                        "dynamicUniforms"
                ),

                // `GETSTATIC RenderSystem.samplerCache` inside `RenderSystem.getSamplerCache()`
                new StaticFieldGetToCondyTransformer(targetMethod(
                        "com/mojang/blaze3d/systems/RenderSystem",
                        "getSamplerCache",
                        "()Lcom/mojang/blaze3d/systems/SamplerCache;"
                )),

                new SingletonAccessedFieldsTransformer(
                        targetClass("net/minecraft/client/Camera"),
                        new ConstantDynamic(
                                "CAMERA_INSTANCE",
                                "Lnet/minecraft/client/Camera;",
                                NULL_CHECK_ON_CONDY_RESOLVE
                                        ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                        : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                                        NULL_CHECK_ON_CONDY_RESOLVE
                                                ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                                : AnacondyBootstraps.HANDLE_BSM_INVOKE,
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
                new AnacondyWorkaroundTransformers.MakeFieldAccessibleTransformer(
                        targetClass("net/minecraft/client/renderer/GameRenderer"),
                        "mainCamera",
                        "Lnet/minecraft/client/Camera;"
                ),
                //endregion

                //region Forge
                new StaticFieldGetToCondyTransformer(
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

    /**
     * Rewrites {@code GETSTATIC singletonInstance} inside target methods to instead load a ConstantDynamic that
     * resolves to the same field, allowing the JVM to perform constant folding optimisations on it.
     * @param targetMethod the method containing the static field get instruction to be transformed
     * @param getStaticName the name of the static field being accessed
     */
    record StaticFieldGetToCondyTransformer(ITransformer.Target targetMethod, String getStaticName, String condyName)
            implements Transformer<MethodNode>, ITransformer<MethodNode> {
        /**
         * Assumes the field name is simply a camelCase version of the method name without the "get" prefix.
         * <p>The CONDY name will be the screaming snake case version of that.</p>
         */
        StaticFieldGetToCondyTransformer(ITransformer.Target targetMethod) {
            this(
                    targetMethod,
                    targetMethod.elementName().substring(3, 4).toLowerCase(Locale.ROOT)
                            + targetMethod.elementName().substring(4)
            );
        }

        StaticFieldGetToCondyTransformer(ITransformer.Target targetMethod, String getStaticName) {
            this(
                    targetMethod,
                    getStaticName,
                    Utils.camelCaseToScreamingSnakeCase(getStaticName)
            );
        }

        @Override
        public @NotNull MethodNode transform(MethodNode methodNode, ITransformerVotingContext context) {
            var insns = methodNode.instructions.iterator();
            while (insns.hasNext()) {
                var insn = insns.next();
                if (!(insn instanceof FieldInsnNode fieldInsn
                        && fieldInsn.getOpcode() == Opcodes.GETSTATIC
                        && fieldInsn.name.equals(getStaticName)
                        && fieldInsn.owner.equals(targetMethod.className())))
                    continue;

                insns.set(new LdcInsnNode(new ConstantDynamic(
                        condyName,
                        fieldInsn.desc,
                        NULL_CHECK_ON_CONDY_RESOLVE
                                ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL
                                : AnacondyBootstraps.HANDLE_BSM_INVOKE,
                        new Handle(Opcodes.H_GETSTATIC, fieldInsn.owner, fieldInsn.name, fieldInsn.desc, false)
                )));
                TOTAL_REWRITES.getAndIncrement();
            }

//            LOGGER.info("");
//            LOGGER.info(targetMethod.elementName());
//            LOGGER.info(ASMAPI.methodNodeToString(methodNode));

            return methodNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetMethod);
        }
    }

    // Todo: Look into supporting chaining of singleton accessed fields from other classes
    //       e.g.: Minecraft.levelRenderer.levelRenderState.entityRenderStates or this.minecraft.options.getCameraType()
    /**
     * Rewrites {@code GETFIELD this.finalField} instructions inside the singleton classes to instead use ConstantDynamics
     * that access the final fields via the singleton instance, effectively turning them into trusted final fields.
     */
    record SingletonAccessedFieldsTransformer(
            ITransformer.Target targetClass,
            ConstantDynamic singletonAccessorCondy,
            Predicate<String> isBlacklisted
    ) implements Transformer<ClassNode>, ITransformer<ClassNode> {
        SingletonAccessedFieldsTransformer(ITransformer.Target targetClass, ConstantDynamic singletonAccessorCondy) {
            this(targetClass, singletonAccessorCondy, Set.of(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.INIT_NAME, "close"));
        }

        SingletonAccessedFieldsTransformer(Target targetClass, ConstantDynamic singletonAccessorCondy, Set<String> blacklistedMethods) {
            this(targetClass, singletonAccessorCondy, blacklistedMethods::contains);
        }

        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            Set<FieldNode> instanceFinalFields = classNode.fields.stream()
                    .filter(fieldNode -> (fieldNode.access & Opcodes.ACC_FINAL) != 0)
                    .filter(fieldNode -> (fieldNode.access & Opcodes.ACC_STATIC) == 0)
                    .collect(Collectors.toUnmodifiableSet());

            Set<String> instanceFinalFieldNames = instanceFinalFields.stream()
                    .map(fieldNode -> fieldNode.name)
                    .collect(Collectors.toUnmodifiableSet());

            Set<String> nullableFieldNames = NULL_CHECK_ON_CONDY_RESOLVE
                    ? instanceFinalFields.stream()
                    .filter(fieldNode -> fieldNode.visibleTypeAnnotations != null)
                    .filter(fieldNode -> fieldNode.visibleTypeAnnotations.stream()
                            .anyMatch(typeAnno -> typeAnno.desc.endsWith("/Nullable;")))
                    .map(fieldNode -> fieldNode.name)
                    .collect(Collectors.toUnmodifiableSet())
                    : Set.of();

            for (var methodNode : classNode.methods) {
                if (isBlacklisted.test(methodNode.name)) continue;
                if (methodNode.name.contains("$")) continue; // skip lambdas, anonymous classes, etc.

                var insns = methodNode.instructions.iterator();
                while (insns.hasNext()) {
                    var insn = insns.next();
                    if (!(insn instanceof FieldInsnNode fieldInsn
                            && fieldInsn.getOpcode() == Opcodes.GETFIELD
                            && fieldInsn.owner.equals(targetClass.className())
                            && instanceFinalFieldNames.contains(fieldInsn.name)))
                        continue;

                    // First replace the ALOAD 0/instance grab insn with a NOP
                    Utils.removePreviousInsnsIfSingletonInstanceLoad(insns);

                    // Then replace with a CONDY that accesses the field using the singleton instance
                    insns.set(new LdcInsnNode(new ConstantDynamic(
                            Utils.camelCaseToScreamingSnakeCase(fieldInsn.name),
                            fieldInsn.desc,
                            // Null-check only if the field is not meant to be nullable
                            NULL_CHECK_ON_CONDY_RESOLVE && !nullableFieldNames.contains(fieldInsn.name)
                                    ? AnacondyBootstraps.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                    : AnacondyBootstraps.HANDLE_BSM_INVOKE,
                            new Handle(
                                    Opcodes.H_GETFIELD,
                                    targetClass.className(),
                                    fieldInsn.name,
                                    fieldInsn.desc,
                                    false
                            ),
                            singletonAccessorCondy
                    )));
                    TOTAL_REWRITES.getAndIncrement();
                }
            }

//            LOGGER.info("Anacondy constant folded " + TOTAL_REWRITES + " final fields");

            return classNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetClass);
        }
    }

    // Todo: Generalise this to work for any specified singleton class, not just Minecraft
    /**
     * Rewrites {@code GETFIELD minecraft} and similar instructions to instead call {@code Minecraft.getInstance()},
     * expanding the benefit of constant folding optimisations via the ConstantDynamic in that method made by
     * {@link StaticFieldGetToCondyTransformer}.
     */
    static final class MinecraftClientFieldCopiesTransformer implements Transformer<ClassNode>, ITransformer<ClassNode> {
        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            for (var methodNode : classNode.methods) {
                if (methodNode.name.equals(ConstantDescs.CLASS_INIT_NAME)) continue;

                var insns = methodNode.instructions.iterator();
                while (insns.hasNext()) {
                    var insn = insns.next();
                    if (!(insn instanceof FieldInsnNode fieldInsn
                            && fieldInsn.desc.equals("Lnet/minecraft/client/Minecraft;")))
                        continue;

                    if (fieldInsn.getOpcode() == Opcodes.GETFIELD) {
                        // First replace the ALOAD 0 with NOP
                        Utils.removePreviousALoad0IfPresent(insns);

                        // Then replace with a call to Minecraft.getInstance()
                        insns.set(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                TARGET_MC_GET_INSTANCE_METHOD.className(),
                                TARGET_MC_GET_INSTANCE_METHOD.elementName(),
                                TARGET_MC_GET_INSTANCE_METHOD.elementDescriptor(),
                                false
                        ));
                        TOTAL_REWRITES.getAndIncrement();
                    }
                }
            }

//            LOGGER.info("Anacondy constant folded " + TOTAL_REWRITES + " fields");

            return classNode;
        }

        // todo: make this global for all MC client classes, or figure out a way to find all classes that reference the
        //       Minecraft instance at build time to avoid needing to manually find and list them here each MC version
        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(
                    targetClass("com/mojang/blaze3d/platform/FramerateLimitTracker"),

                    targetClass("com/mojang/realmsclient/client/RealmsClient"),

                    targetClass("net/minecraft/client/GameNarrator"),
                    targetClass("net/minecraft/client/KeyboardHandler"),
                    targetClass("net/minecraft/client/MouseHandler"),
                    targetClass("net/minecraft/client/Options"),
                    targetClass("net/minecraft/client/PeriodicNotificationManager$NotificationTask"),

                    targetClass("net/minecraft/client/gui/BundleMouseActions"),
                    targetClass("net/minecraft/client/gui/Gui"),
                    targetClass("net/minecraft/client/gui/GuiGraphics"),

                    targetClass("net/minecraft/client/gui/components/AbstractSelectionList"),
                    targetClass("net/minecraft/client/gui/components/BossHealthOverlay"),
                    targetClass("net/minecraft/client/gui/components/ChatComponent"),
                    targetClass("net/minecraft/client/gui/components/CommandSuggestions"),
                    targetClass("net/minecraft/client/gui/components/DebugScreenOverlay"),
                    targetClass("net/minecraft/client/gui/components/ItemDisplayWidget"),
                    targetClass("net/minecraft/client/gui/components/PlayerTabOverlay"),
                    targetClass("net/minecraft/client/gui/components/ScrollableLayout$Container"),
                    targetClass("net/minecraft/client/gui/components/SubtitleOverlay"),

                    targetClass("net/minecraft/client/gui/components/spectator/SpectatorGui"),

                    targetClass("net/minecraft/client/gui/components/toasts/NowPlayingToast"),
                    targetClass("net/minecraft/client/gui/components/toasts/ToastManager"),

                    targetClass("net/minecraft/client/gui/contextualbar/ExperienceBarRenderer"),
                    targetClass("net/minecraft/client/gui/contextualbar/JumpableVehicleBarRenderer"),
                    targetClass("net/minecraft/client/gui/contextualbar/LocatorBarRenderer"),

                    targetClass("net/minecraft/client/gui/screens/LoadingOverlay"),
                    targetClass("net/minecraft/client/gui/screens/Screen"),

                    targetClass("net/minecraft/client/gui/screens/advancements/AdvancementTab"),
                    targetClass("net/minecraft/client/gui/screens/advancements/AdvancementWidget"),

                    targetClass("net/minecraft/client/gui/screens/inventory/CreativeInventoryListener"),
                    targetClass("net/minecraft/client/gui/screens/inventory/EffectsInInventory"),

                    targetClass("net/minecraft/client/gui/screens/multiplayer/ServerSelectionList$LANHeader"),
                    targetClass("net/minecraft/client/gui/screens/multiplayer/ServerSelectionList$NetworkServerEntry"),
                    targetClass("net/minecraft/client/gui/screens/multiplayer/ServerSelectionList$OnlineServerEntry"),

                    targetClass("net/minecraft/client/gui/screens/packs/TransferableSelectionList$PackEntry"),

                    targetClass("net/minecraft/client/gui/screens/recipebook/RecipeBookComponent"),
                    targetClass("net/minecraft/client/gui/screens/recipebook/RecipeBookPage"),

                    targetClass("net/minecraft/client/gui/screens/social/PlayerEntry"),
                    targetClass("net/minecraft/client/gui/screens/social/PlayerSocialManager"),

                    targetClass("net/minecraft/client/gui/screens/worldselection/WorldOpenFlows"),
                    targetClass("net/minecraft/client/gui/screens/worldselection/WorldSelectionList$Builder"),
                    targetClass("net/minecraft/client/gui/screens/worldselection/WorldSelectionList$LoadingHeader"),
                    targetClass("net/minecraft/client/gui/screens/worldselection/WorldSelectionList$WorldListEntry"),

                    targetClass("net/minecraft/client/multiplayer/ClientAdvancements"),
                    targetClass("net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl"),
                    targetClass("net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl"),
                    targetClass("net/minecraft/client/multiplayer/ClientLevel"),
                    targetClass("net/minecraft/client/multiplayer/ClientSuggestionProvider"),
                    targetClass("net/minecraft/client/multiplayer/MultiPlayerGameMode"),
                    targetClass("net/minecraft/client/multiplayer/ServerList"),
                    targetClass("net/minecraft/client/multiplayer/chat/ChatListener"),

                    targetClass("net/minecraft/client/player/LocalPlayer"),
                    targetClass("net/minecraft/client/player/LocalPlayerResolver"),

                    targetClass("net/minecraft/client/renderer/GameRenderer"),
                    targetClass("net/minecraft/client/renderer/ItemInHandRenderer"),
                    targetClass("net/minecraft/client/renderer/LevelEventHandler"),
                    targetClass("net/minecraft/client/renderer/LevelRenderer"),
                    targetClass("net/minecraft/client/renderer/LightTexture"),
                    targetClass("net/minecraft/client/renderer/PanoramaRenderer"),
                    targetClass("net/minecraft/client/renderer/ScreenEffectRenderer"),
                    targetClass("net/minecraft/client/renderer/VirtualScreen"),

                    targetClass("net/minecraft/client/resources/server/DownloadedPackSource"),

                    targetClass("net/minecraft/client/server/IntegratedServer"),

                    targetClass("net/minecraft/client/sounds/MusicManager"),

                    targetClass("net/minecraft/client/telemetry/ClientTelemetryManager")
            );
        }
    }
}
