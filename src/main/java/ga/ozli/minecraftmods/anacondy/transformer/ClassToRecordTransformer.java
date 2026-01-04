package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import net.minecraftforge.coremod.api.ASMAPI;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.TypeDescriptor;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;

final class ClassToRecordTransformer implements Transformer<ClassNode>, ITransformer<ClassNode> {
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

        if (classNode.recordComponents == null)
            throw new IllegalArgumentException("Cannot convert class " + classNode.name + " to record because it has no final instance fields.");

        // Mark class as a record
        classNode.access |= Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;

        if (classNode.superName != null && !classNode.superName.equals("java/lang/Object"))
            throw new IllegalArgumentException("Cannot convert class " + classNode.name + " to record because it already has a superclass: " + classNode.superName);

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

                targetClass("com/mojang/blaze3d/resources/CrossFrameResourcePool"),
                targetClass("com/mojang/blaze3d/resources/CrossFrameResourcePool$ResourceEntry"),

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

                // commented out as Brigadier is not on the game layer and is therefore unable to be transformed
//                targetClass("com/mojang/brigadier/LiteralMessage"),

                targetClass("com/mojang/datafixers/util/Pair"),

                targetClass("net/minecraft/client/Camera"),
                targetClass("net/minecraft/client/Camera$NearPlane"),
                targetClass("net/minecraft/client/DeltaTracker$DefaultValue"),
                targetClass("net/minecraft/client/DeltaTracker$Timer"),
                targetClass("net/minecraft/client/KeyboardHandler"),
                targetClass("net/minecraft/client/MouseHandler"),
                targetClass("net/minecraft/client/Options"),
                targetClass("net/minecraft/client/StringSplitter$FlatComponents"),
                targetClass("net/minecraft/client/StringSplitter$LineComponent"),
                targetClass("net/minecraft/client/User"),

                targetClass("net/minecraft/client/animation/KeyframeAnimation"),

                targetClass("net/minecraft/client/color/block/BlockColors"),
                targetClass("net/minecraft/client/color/block/BlockTintCache"),

                targetClass("net/minecraft/client/model/geom/EntityModelSet"),
                targetClass("net/minecraft/client/model/geom/ModelPart"),
                targetClass("net/minecraft/client/model/geom/ModelPart$Cube"),

                targetClass("net/minecraft/client/multiplayer/LevelLoadTracker"),
                targetClass("net/minecraft/client/multiplayer/MultiPlayerGameMode"),

                targetClass("net/minecraft/client/gui/Gui"),
                targetClass("net/minecraft/client/gui/GuiGraphics$ScissorStack"),

                // Todo: this.font inside DebugScreenOverlay that is passed to FpsDebugChart and friends comes from the Minecraft singleton instance
                targetClass("net/minecraft/client/gui/components/DebugScreenOverlay"),

                targetClass("net/minecraft/client/gui/components/debug/DebugEntryMemory"),
                targetClass("net/minecraft/client/gui/components/debug/DebugEntryNoop"),
                targetClass("net/minecraft/client/gui/components/debug/DebugScreenEntryList"),

                targetClass("net/minecraft/client/gui/components/debugchart/ProfilerPieChart"),

                targetClass("net/minecraft/client/gui/font/FontOption$Filter"),
                targetClass("net/minecraft/client/gui/font/CodepointMap"),
                targetClass("net/minecraft/client/gui/font/FontTexture$Node"),

                targetClass("net/minecraft/client/gui/font/glyphs/BakedSheetGlyph"),
                targetClass("net/minecraft/client/gui/font/glyphs/EmptyGlyph"),

                targetClass("net/minecraft/client/gui/render/GuiRenderer"),
                targetClass("net/minecraft/client/gui/render/GuiRenderer$AtlasPosition"),

                targetClass("net/minecraft/client/gui/render/state/GuiRenderState"),
                targetClass("net/minecraft/client/gui/render/state/GuiRenderState$Node"),

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
                targetClass("net/minecraft/client/renderer/chunk/VisGraph"),
                targetClass("net/minecraft/client/renderer/chunk/VisibilitySet"),

                targetClass("net/minecraft/client/renderer/culling/Frustum"),

                targetClass("net/minecraft/client/renderer/debug/DebugRenderer"),

                targetClass("net/minecraft/client/renderer/entity/EntityRenderDispatcher"),

                targetClass("net/minecraft/client/renderer/feature/BlockFeatureRenderer"),
                targetClass("net/minecraft/client/renderer/feature/CustomFeatureRenderer$Storage"),
                targetClass("net/minecraft/client/renderer/feature/FeatureRenderDispatcher"),
                targetClass("net/minecraft/client/renderer/feature/ItemFeatureRenderer"),
                targetClass("net/minecraft/client/renderer/feature/ModelFeatureRenderer"),
                targetClass("net/minecraft/client/renderer/feature/ModelFeatureRenderer$Storage"),
                targetClass("net/minecraft/client/renderer/feature/ModelPartFeatureRenderer"),
                targetClass("net/minecraft/client/renderer/feature/ModelPartFeatureRenderer$Storage"),
                targetClass("net/minecraft/client/renderer/feature/NameTagFeatureRenderer$Storage"),
                targetClass("net/minecraft/client/renderer/feature/ParticleFeatureRenderer"),

                targetClass("net/minecraft/client/renderer/fog/FogRenderer"),

                targetClass("net/minecraft/client/renderer/rendertype/RenderSetup"),
                targetClass("net/minecraft/client/renderer/rendertype/LayeringTransform"),
                targetClass("net/minecraft/client/renderer/rendertype/OutputTarget"),
                targetClass("net/minecraft/client/renderer/rendertype/RenderType"),

                // Todo: This causes the game to crash if UnitTextureAtlasSprite is classloaded
//                    targetClass("net/minecraft/client/renderer/texture/TextureAtlasSprite"),

                targetClass("net/minecraft/client/renderer/texture/OverlayTexture"),
                targetClass("net/minecraft/client/renderer/texture/TextureManager"),

                targetClass("net/minecraft/client/renderer/state/LevelRenderState"),
                targetClass("net/minecraft/client/renderer/state/WeatherRenderState"),

                targetClass("net/minecraft/client/renderer/CubeMap"),
                targetClass("net/minecraft/client/renderer/CachedPerspectiveProjectionMatrixBuffer"),
                targetClass("net/minecraft/client/renderer/GameRenderer"),
                targetClass("net/minecraft/client/renderer/GlobalSettingsUniform"),
                targetClass("net/minecraft/client/renderer/ItemInHandRenderer"),
                targetClass("net/minecraft/client/renderer/LevelEventHandler"),
                targetClass("net/minecraft/client/renderer/LevelRenderer"),
                targetClass("net/minecraft/client/renderer/LightTexture"),
                targetClass("net/minecraft/client/renderer/MappableRingBuffer"),
                targetClass("net/minecraft/client/renderer/MultiBufferSource$BufferSource"),
                targetClass("net/minecraft/client/renderer/OutlineBufferSource"),
                targetClass("net/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer"),
                targetClass("net/minecraft/client/renderer/RenderBuffers"),
                targetClass("net/minecraft/client/renderer/SectionBufferBuilderPack"),
                targetClass("net/minecraft/client/renderer/SectionBufferBuilderPool"),
                targetClass("net/minecraft/client/renderer/SectionOcclusionGraph"),
                targetClass("net/minecraft/client/renderer/SectionOcclusionGraph$GraphStorage"),
                targetClass("net/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap"),
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

                targetClass("net/minecraft/network/PacketProcessor"),
                targetClass("net/minecraft/network/ServerConnectionListener"),

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

                targetClass("net/minecraft/server/level/WorldGenRegion"),

                targetClass("net/minecraft/server/level/biome/BiomeManager"),

                targetClass("net/minecraft/server/level/progress/LevelLoadProgressTracker"),

                targetClass("net/minecraft/stats/StatType"),

                targetClass("net/minecraft/tags/TagEntry"),

                targetClass("net/minecraft/util/SegmentedAnglePrecision"),
                targetClass("net/minecraft/util/SimpleBitStorage"),
                targetClass("net/minecraft/util/StaticCache2D"),
                targetClass("net/minecraft/util/ThreadingDetector"),
                targetClass("net/minecraft/util/TickThrottler"),
                targetClass("net/minecraft/util/ZeroBitStorage"),

                targetClass("net/minecraft/util/context/ContextKey"),
                targetClass("net/minecraft/util/context/ContextKeySet"),
                targetClass("net/minecraft/util/context/ContextMap"),

                targetClass("net/minecraft/util/profiling/Zone"),

                targetClass("net/minecraft/world/attribute/EnvironmentAttribute"),

                targetClass("net/minecraft/world/entity/ai/Brain$MemoryValue"),
                targetClass("net/minecraft/world/entity/ai/Brain$Provider"),

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
                targetClass("net/minecraft/world/level/ClipContext"),
                targetClass("net/minecraft/world/level/DataPackConfig"),
                targetClass("net/minecraft/world/level/LevelSettings"),
                targetClass("net/minecraft/world/level/StructureManager"),

                targetClass("net/minecraft/world/level/biome/BiomeGenerationSettings"),

                targetClass("net/minecraft/world/level/chunk/PalettedContainer"),

                targetClass("net/minecraft/world/level/levelgen/Beardifier"),
                targetClass("net/minecraft/world/level/levelgen/BelowZeroRetrogen"),
                targetClass("net/minecraft/world/level/levelgen/GeodeBlockSettings"),
                targetClass("net/minecraft/world/level/levelgen/GeodeCrackSettings"),
                targetClass("net/minecraft/world/level/levelgen/GeodeLayerSettings"),
                targetClass("net/minecraft/world/level/levelgen/Heightmap"),
                targetClass("net/minecraft/world/level/levelgen/MarsagliaPolarGaussian"),
                targetClass("net/minecraft/world/level/levelgen/SingleThreadedRandomSource"),
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

                targetClass("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate"),
                targetClass("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$Palette"),
                targetClass("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$SimplePalette"),
                targetClass("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureEntityInfo"),
                targetClass("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager"),

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
