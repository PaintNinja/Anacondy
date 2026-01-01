package ga.ozli.minecraftmods.anacondytransformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.minecraftforge.coremod.api.ASMAPI;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.TypeDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

                new ClassToRecordTransformer(),

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

                // Lighting done inside ClassToRecordTransformer instead
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

    static final class ClassToRecordTransformer implements Transformer<ClassNode>, ITransformer<ClassNode> {
        private static final Handle HANDLE_BSM_OBJECT_METHODS = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/runtime/ObjectMethods",
                "bootstrap",
                MethodTypeDesc.of(
                        ConstantDescs.CD_Object,
                        ConstantDescs.CD_MethodHandles_Lookup,
                        ConstantDescs.CD_String,
                        TypeDescriptor.class.describeConstable().orElseThrow(),
                        ConstantDescs.CD_Class,
                        ConstantDescs.CD_String,
                        ConstantDescs.CD_MethodHandle.arrayType()
                ).descriptorString(),
                false
        );

        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            for (var fieldNode : classNode.fields) {
                if ((fieldNode.access & Opcodes.ACC_STATIC) != 0) continue; // skip static fields

                // Add record component for each instance final field
                classNode.visitRecordComponent(fieldNode.name, fieldNode.desc, fieldNode.signature);

                // Add accessor method for each instance final field if not already present
                if (ASMAPI.findMethodNode(classNode, fieldNode.name, "()" + fieldNode.desc) == null) {
                    var accessorMethod = new MethodNode(
                            Opcodes.ACC_PUBLIC,
                            fieldNode.name,
                            "()" + fieldNode.desc,
                            null,
                            null
                    );
                    accessorMethod.visitCode();
                    accessorMethod.visitVarInsn(Opcodes.ALOAD, 0);
                    accessorMethod.visitFieldInsn(Opcodes.GETFIELD, classNode.name, fieldNode.name, fieldNode.desc);
                    Utils.visitReturnInsn(accessorMethod, fieldNode.desc);
                    accessorMethod.visitEnd();
                    classNode.methods.add(accessorMethod);
                }
            }

            // Mark class as a record
            classNode.access |= Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;

            if (classNode.superName != null && !classNode.superName.equals("java/lang/Object"))
                throw new IllegalStateException("Cannot convert class " + classNode.name + " to record because it already has a superclass: " + classNode.superName);

            classNode.superName = "java/lang/Record";

            // Make the canonical constructor if needed. If it already exists, just update its access modifiers to match the class'
            int canonicalCtorAccess = classNode.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            var canonicalCtorDesc = classNode.recordComponents.stream()
                    .map(recordComponentNode -> recordComponentNode.descriptor)
                    .collect(Collectors.joining("", "(", ")V"));
            var canonicalCtorNode = ASMAPI.findMethodNode(classNode, ConstantDescs.INIT_NAME, canonicalCtorDesc);
            if (canonicalCtorNode == null) {
                canonicalCtorNode = new MethodNode(
                        canonicalCtorAccess,
                        ConstantDescs.INIT_NAME,
                        canonicalCtorDesc,
                        null,
                        null
                );
                canonicalCtorNode.visitCode();
                canonicalCtorNode.visitVarInsn(Opcodes.ALOAD, 0);
                // Call super constructor
                canonicalCtorNode.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        classNode.superName,
                        ConstantDescs.INIT_NAME,
                        "()V",
                        false
                );
                // Assign fields from parameters
                int paramIndex = 1;
                for (var recordComponentNode : classNode.recordComponents) {
                    canonicalCtorNode.visitVarInsn(Opcodes.ALOAD, 0);
                    Utils.visitLoadVarInsn(canonicalCtorNode, recordComponentNode.descriptor, paramIndex++);
                    canonicalCtorNode.visitFieldInsn(
                            Opcodes.PUTFIELD,
                            classNode.name,
                            recordComponentNode.name,
                            recordComponentNode.descriptor
                    );
                }
            } else {
                canonicalCtorNode.access = canonicalCtorAccess;
            }

            // Ensure all constructors call super() on Record, not Object
            for (var methodNode : classNode.methods) {
                if (!methodNode.name.equals(ConstantDescs.INIT_NAME)) continue;

                for (var insn : methodNode.instructions) {
                    if (insn instanceof MethodInsnNode methodInsn
                            && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                            && methodInsn.name.equals(ConstantDescs.INIT_NAME)
                            && methodInsn.owner.equals("java/lang/Object")) {
                        methodInsn.owner = "java/lang/Record";
                    }
                }
            }

            // Add equals, hashCode, and toString methods if not already present
            var hasEquals = ASMAPI.findMethodNode(classNode, "equals", "(Ljava/lang/Object;)Z") != null;
            if (!hasEquals) {
                var equalsMethod = new MethodNode(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
                equalsMethod.visitCode();
                equalsMethod.visitVarInsn(Opcodes.ALOAD, 0);
                equalsMethod.visitVarInsn(Opcodes.ALOAD, 1);

                // While records are intended to compare all record components inside their equals() method, doing so
                // breaks some classes we transform where their identity equals and hashcode is relied upon.

                // Therefore, we generate methods that implement the existing behaviour to stay consistent with that
                // while still passing the requirement of records implementing these methods.

                // return this == that
                var label = new Label();
                equalsMethod.visitJumpInsn(Opcodes.IF_ACMPEQ, label);
                equalsMethod.visitInsn(Opcodes.ICONST_0);
                equalsMethod.visitInsn(Opcodes.IRETURN);
                equalsMethod.visitLabel(label);
                equalsMethod.visitInsn(Opcodes.ICONST_1);
                equalsMethod.visitInsn(Opcodes.IRETURN);
                classNode.methods.add(equalsMethod);
            }

            var hasHashCode = ASMAPI.findMethodNode(classNode, "hashCode", "()I") != null;
            if (!hasHashCode) {
                var hashCodeMethod = new MethodNode(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
                hashCodeMethod.visitCode();
                hashCodeMethod.visitVarInsn(Opcodes.ALOAD, 0);

                // call System.identityHashCode(this) to preserve existing behaviour, see explanation in equals() impl above
                hashCodeMethod.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "identityHashCode",
                        "(Ljava/lang/Object;)I",
                        false
                );
                hashCodeMethod.visitInsn(Opcodes.IRETURN);
                classNode.methods.add(hashCodeMethod);
            }

            var hasToString = ASMAPI.findMethodNode(classNode, "toString", "()Ljava/lang/String;") != null;
            if (!hasToString) {
                var toStringMethod = new MethodNode(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
                toStringMethod.visitCode();
                toStringMethod.visitVarInsn(Opcodes.ALOAD, 0);

                // Need to generate an INDY call to ObjectMethods.bootstrap() for toString() impl

                // But first, prepare the bootstrap arguments
                var bootstrapArgs = new ArrayList<>();
                bootstrapArgs.add(Type.getObjectType(classNode.name)); // the class type we're generating toString for
                // the record component names, separated by semicolons
                bootstrapArgs.add(classNode.recordComponents.stream()
                        .map(recordComponentNode -> recordComponentNode.name)
                        .collect(Collectors.joining(";"))
                );
                // the accessor handles for each record component
                for (var recordComponentNode : classNode.recordComponents) {
                    bootstrapArgs.add(new Handle(
                            Opcodes.H_GETFIELD,
                            classNode.name,
                            recordComponentNode.name,
                            recordComponentNode.descriptor,
                            false
                    ));
                }

                toStringMethod.visitInvokeDynamicInsn(
                        "toString",
                        "(L" + classNode.name + ";)Ljava/lang/String;",
                        HANDLE_BSM_OBJECT_METHODS,
                        bootstrapArgs.toArray()
                );
                toStringMethod.visitInsn(Opcodes.ARETURN);
                classNode.methods.add(toStringMethod);
            }

            return classNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(
                    targetClass("com/mojang/blaze3d/audio/Channel"),

                    targetClass("com/mojang/blaze3d/buffers/Std140Builder"),

                    targetClass("com/mojang/blaze3d/font/SpaceProvider"),

                    targetClass("com/mojang/blaze3d/framegraph/FrameGraphBuilder"),
                    targetClass("com/mojang/blaze3d/framegraph/FrameGraphBuilder$Handle"),
                    targetClass("com/mojang/blaze3d/framegraph/FrameGraphBuilder$Pass"),

                    targetClass("com/mojang/blaze3d/opengl/GlBuffer$GlMappedView"),
                    targetClass("com/mojang/blaze3d/opengl/GlCommandEncoder"),
                    targetClass("com/mojang/blaze3d/opengl/GlDevice"),
                    targetClass("com/mojang/blaze3d/opengl/GlProgram"),
                    targetClass("com/mojang/blaze3d/opengl/GlRenderPass"),
                    targetClass("com/mojang/blaze3d/opengl/GlShaderModule"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$BlendState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$BooleanState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$ColorLogicState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$CullState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$DepthState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$PolygonOffsetState"),
                    targetClass("com/mojang/blaze3d/opengl/GlStateManager$ScissorState"),
                    targetClass("com/mojang/blaze3d/opengl/VertexArrayCache$VertexArray"),

                    targetClass("com/mojang/blaze3d/pipeline/MainTarget$Dimension"),
                    targetClass("com/mojang/blaze3d/pipeline/RenderPipeline"),

                    targetClass("com/mojang/blaze3d/platform/FramerateLimitTracker"),
                    targetClass("com/mojang/blaze3d/platform/Lighting"),
                    targetClass("com/mojang/blaze3d/platform/Monitor"),
                    targetClass("com/mojang/blaze3d/platform/NativeImage"),
                    targetClass("com/mojang/blaze3d/platform/ScreenManager"),
                    targetClass("com/mojang/blaze3d/platform/VideoMode"),

                    targetClass("com/mojang/blaze3d/platform/cursor/CursorType"),

                    targetClass("com/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer"),

                    targetClass("com/mojang/blaze3d/vertex/BufferBuilder"),
                    targetClass("com/mojang/blaze3d/vertex/CompactVectorArray"),
                    targetClass("com/mojang/blaze3d/vertex/MeshData"),
                    targetClass("com/mojang/blaze3d/vertex/PoseStack"),
                    targetClass("com/mojang/blaze3d/vertex/PoseStack$Pose"),
                    targetClass("com/mojang/blaze3d/vertex/Tesselator"),
                    targetClass("com/mojang/blaze3d/vertex/VertexFormat"),
                    targetClass("com/mojang/blaze3d/vertex/VertexMultiConsumer$Double"),

                    targetClass("com/mojang/math/Divisor"),
                    targetClass("com/mojang/math/Transformation"),

                    targetClass("com/mojang/datafixers/util/Pair"),

                    targetClass("net/minecraft/client/Options"),
                    targetClass("net/minecraft/client/User"),

                    targetClass("net/minecraft/client/animation/KeyframeAnimation"),

                    targetClass("net/minecraft/client/color/block/BlockColors"),
                    targetClass("net/minecraft/client/color/block/BlockTintCache"),

                    // Todo: this.font inside DebugScreenOverlay that is passed to FpsDebugChart and friends comes from the Minecraft singleton instance
                    targetClass("net/minecraft/client/gui/components/DebugScreenOverlay"),

                    targetClass("net/minecraft/client/gui/components/debugchart/ProfilerPieChart"),

                    targetClass("net/minecraft/client/gui/font/FontOption$Filter"),

                    targetClass("net/minecraft/client/gui/font/glyphs/BakedSheetGlyph"),
                    targetClass("net/minecraft/client/gui/font/glyphs/EmptyGlyph"),

                    targetClass("net/minecraft/client/renderer/block/BlockRenderDispatcher"),
                    targetClass("net/minecraft/client/renderer/block/LiquidBlockRenderer"),
                    targetClass("net/minecraft/client/renderer/block/ModelBlockRenderer"),
                    targetClass("net/minecraft/client/renderer/block/ModelBlockRenderer$Cache"),

                    targetClass("net/minecraft/client/renderer/chunk/CompiledSectionMesh"),
                    targetClass("net/minecraft/client/renderer/chunk/RenderRegionCache"),
                    targetClass("net/minecraft/client/renderer/chunk/RenderSectionRegion"),
                    targetClass("net/minecraft/client/renderer/chunk/SectionCompiler"),
                    targetClass("net/minecraft/client/renderer/chunk/SectionCompiler$Results"),
                    targetClass("net/minecraft/client/renderer/chunk/SectionCopy"),

                    targetClass("net/minecraft/client/renderer/culling/Frustum"),

                    targetClass("net/minecraft/client/renderer/fog/FogRenderer"),

                    targetClass("net/minecraft/client/renderer/rendertype/RenderSetup"),
                    targetClass("net/minecraft/client/renderer/rendertype/LayeringTransform"),
                    targetClass("net/minecraft/client/renderer/rendertype/OutputTarget"),
                    targetClass("net/minecraft/client/renderer/rendertype/RenderType"),

                    // Todo: This causes the game to crash if UnitTextureAtlasSprite is classloaded
//                    targetClass("net/minecraft/client/renderer/texture/TextureAtlasSprite"),
                    
                    targetClass("net/minecraft/client/renderer/texture/OverlayTexture"),
                    targetClass("net/minecraft/client/renderer/texture/TextureManager"),

                    targetClass("net/minecraft/client/renderer/CubeMap"),
                    targetClass("net/minecraft/client/renderer/CachedPerspectiveProjectionMatrixBuffer"),
                    targetClass("net/minecraft/client/renderer/GlobalSettingsUniform"),
                    targetClass("net/minecraft/client/renderer/LevelEventHandler"),
                    targetClass("net/minecraft/client/renderer/LightTexture"),
                    targetClass("net/minecraft/client/renderer/MappableRingBuffer"),
                    targetClass("net/minecraft/client/renderer/SkyRenderer"),
                    targetClass("net/minecraft/client/renderer/SpecialBlockModelRenderer"),
                    targetClass("net/minecraft/client/renderer/SpriteCoordinateExpander"),
                    targetClass("net/minecraft/client/renderer/SubmitNodeCollection"),
                    targetClass("net/minecraft/client/renderer/SubmitNodeStorage"),

                    targetClass("net/minecraft/core/Cloner"),
                    targetClass("net/minecraft/core/Cloner$Factory"),
                    targetClass("net/minecraft/core/Cursor3D"),
                    targetClass("net/minecraft/core/RegistrySetBuilder"),

                    targetClass("net/minecraft/core/particles/BlockParticleOption"),
                    targetClass("net/minecraft/core/particles/ColorParticleOption"),
                    targetClass("net/minecraft/core/particles/ItemParticleOption"),
                    targetClass("net/minecraft/core/particles/PowerParticleOption"),
                    targetClass("net/minecraft/core/particles/ShriekParticleOption"),
                    targetClass("net/minecraft/core/particles/SpellParticleOption"),
                    targetClass("net/minecraft/core/particles/VibrationParticleOption"),

                    targetClass("net/minecraft/gizmos/SimpleGizmoCollector"),
                    targetClass("net/minecraft/gizmos/SimpleGizmoCollector$GismoInstance"),

                    targetClass("net/minecraft/nbt/CompoundTag"),

                    targetClass("net/minecraft/network/chat/MutableComponent"),
                    targetClass("net/minecraft/network/chat/FilterMask"),
                    targetClass("net/minecraft/network/chat/Style"),
                    // Todo: Replace Guava ImmutableList.copyOf() with List.copyOf() in SubStringSource ctor
                    targetClass("net/minecraft/network/chat/SubStringSource"),
                    targetClass("net/minecraft/network/chat/TextColor"),

                    targetClass("net/minecraft/network/chat/contents/KeybindContents"),
                    targetClass("net/minecraft/network/chat/contents/NbtContents"),
                    targetClass("net/minecraft/network/chat/contents/TranslatableContents"),

                    targetClass("net/minecraft/resources/Identifier"),
                    targetClass("net/minecraft/resources/RegistryOps$HolderLookupAdapter"),
                    targetClass("net/minecraft/resources/ResourceKey"),

                    targetClass("net/minecraft/stats/StatType"),

                    targetClass("net/minecraft/util/TickThrottler"),
                    targetClass("net/minecraft/util/ZeroBitStorage"),

                    targetClass("net/minecraft/util/context/ContextKey"),
                    targetClass("net/minecraft/util/context/ContextKeySet"),
                    targetClass("net/minecraft/util/context/ContextMap"),

                    targetClass("net/minecraft/world/entity/ai/attributes/AttributeMap"),

                    targetClass("net/minecraft/world/entity/ai/behavior/BlockPosTracker"),
                    targetClass("net/minecraft/world/entity/ai/behavior/DoNothing"),
                    targetClass("net/minecraft/world/entity/ai/behavior/EntityTracker"),
                    targetClass("net/minecraft/world/entity/ai/behavior/ShufflingList"),
                    targetClass("net/minecraft/world/entity/ai/behavior/ShufflingList$WeightedEntry"),

                    targetClass("net/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor"),

                    targetClass("net/minecraft/world/entity/ai/gossip/GossipContainer"),

                    targetClass("net/minecraft/world/entity/ai/memory/ExpirableValue"),
                    targetClass("net/minecraft/world/entity/ai/memory/MemoryModuleType"),
                    targetClass("net/minecraft/world/entity/ai/memory/NearestVisibleLivingEntities"),
                    targetClass("net/minecraft/world/entity/ai/memory/WalkTarget"),

                    targetClass("net/minecraft/world/entity/schedule/Activity"),

                    targetClass("net/minecraft/world/entity/Crackiness"),
                    targetClass("net/minecraft/world/entity/EntityType"),

                    targetClass("net/minecraft/world/flag/FeatureFlagRegistry"),
                    targetClass("net/minecraft/world/flag/FeatureFlagSet"),
                    targetClass("net/minecraft/world/flag/FeatureFlagUniverse"),

                    targetClass("net/minecraft/world/level/ChunkPos"),
                    targetClass("net/minecraft/world/level/ClipBlockStateContext"),
                    targetClass("net/minecraft/world/level/DataPackConfig"),
                    targetClass("net/minecraft/world/level/LevelSettings"),
                    targetClass("net/minecraft/world/level/StructureManager"),

                    targetClass("net/minecraft/world/level/biome/BiomeGenerationSettings"),

                    targetClass("net/minecraft/world/level/levelgen/Beardifier"),
                    targetClass("net/minecraft/world/level/levelgen/BelowZeroRetrogen"),
                    targetClass("net/minecraft/world/level/levelgen/GeodeBlockSettings"),
                    targetClass("net/minecraft/world/level/levelgen/GeodeCrackSettings"),
                    targetClass("net/minecraft/world/level/levelgen/GeodeLayerSettings"),
                    targetClass("net/minecraft/world/level/levelgen/Heightmap"),
                    targetClass("net/minecraft/world/level/levelgen/SurfaceSystem"),
                    targetClass("net/minecraft/world/level/levelgen/WorldOptions"),
                    targetClass("net/minecraft/world/level/levelgen/XoroshiroRandomSource"),
                    targetClass("net/minecraft/world/level/levelgen/XoroshiroRandomSource$XoroshiroPositionalRandomFactory"),

                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/BlockStateConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/ColumnFeatureConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/CountConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/DeltaFeatureConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/DripstoneClusterConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/EndGatewayConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/GeodeConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/HugeMushroomConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/LargeDripstoneConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/LayerConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/MultifaceGrowthConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/PointedDripstoneConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/RandomBooleanFeatureConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/RandomFeatureConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/ReplaceBlockConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/ReplaceSphereConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/RootSystemConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/SimpleRandomFeatureConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/SpikeConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/SpringConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/UnderwaterMagmaConfiguration"),
                    targetClass("net/minecraft/world/level/levelgen/feature/configurations/VegetationPatchConfiguration"),

                    targetClass("net/minecraft/world/level/levelgen/flat/FlatLayerInfo"),

                    targetClass("net/minecraft/world/level/levelgen/presets/WorldPreset"),

                    targetClass("net/minecraft/world/level/levelgen/structure/StructureCheck"),

                    targetClass("net/minecraft/world/level/levelgen/sampler/BlendedNoise"),
                    targetClass("net/minecraft/world/level/levelgen/sampler/NormalNoise"),
                    targetClass("net/minecraft/world/level/levelgen/sampler/ImprovedNoise"),

                    targetClass("net/minecraft/world/level/levelgen/synth/PerlinNoise"),
                    targetClass("net/minecraft/world/level/levelgen/synth/PerlinSimplexNoise"),
                    targetClass("net/minecraft/world/level/levelgen/synth/SimplexNoise"),

                    targetClass("net/minecraft/world/level/lighting/ChunkSkyLightSources"),
                    targetClass("net/minecraft/world/level/lighting/LeveledPriorityQueue"),

                    targetClass("net/minecraft/world/level/material/MapColor"),

                    targetClass("net/minecraft/world/level/pathfinder/Path"),
                    targetClass("net/minecraft/world/level/pathfinder/PathfindingContext"),
                    targetClass("net/minecraft/world/level/pathfinder/PathTypeCache"),

                    targetClass("net/minecraft/world/level/storage/CommandStorage"),
                    targetClass("net/minecraft/world/level/storage/DerivedLevelData"),
                    targetClass("net/minecraft/world/level/storage/DimensionDataStorage"),
                    targetClass("net/minecraft/world/level/storage/LevelResource"),
                    targetClass("net/minecraft/world/level/storage/LevelVersion"),
                    targetClass("net/minecraft/world/level/storage/PlayerDataStorage"),
                    targetClass("net/minecraft/world/level/storage/PrimaryLevelData"),
                    targetClass("net/minecraft/world/level/storage/TagValueInput"),
                    targetClass("net/minecraft/world/level/storage/TagValueInput$CompoundListWrapper"),
                    targetClass("net/minecraft/world/level/storage/TagValueInput$ListWrapper"),
                    targetClass("net/minecraft/world/level/storage/TagValueInput$TypedListWrapper"),
                    targetClass("net/minecraft/world/level/storage/TagValueOutput"),
                    targetClass("net/minecraft/world/level/storage/TagValueOutput$ListWrapper"),
                    targetClass("net/minecraft/world/level/storage/TagValueOutput$TypedListWrapper"),

                    targetClass("net/minecraft/world/level/timers/TimerCallbacks"),
                    targetClass("net/minecraft/world/level/timers/TimerQueue"),
                    targetClass("net/minecraft/world/level/timers/TimerQueue$Event"),

                    targetClass("net/minecraft/world/phys/AABB"),
                    targetClass("net/minecraft/world/phys/Vec2"),
                    targetClass("net/minecraft/world/phys/Vec3"),

                    targetClass("net/minecraft/world/phys/shapes/IdenticalMerger"),

                    targetClass("net/minecraft/world/ticks/LevelChunkTicks"),
                    targetClass("net/minecraft/world/ticks/LevelTicks"),
                    targetClass("net/minecraft/world/ticks/WorldGenTickAccess"),

                    targetClass("net/minecraft/world/timeline/Timeline"),

                    targetClass("net/minecraft/world/CompoundContainer"),
                    targetClass("net/minecraft/world/DifficultyInstance"),
                    targetClass("net/minecraft/world/RandomSequence")
            );
        }
    }
}
