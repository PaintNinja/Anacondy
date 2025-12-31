package ga.ozli.minecraftmods.anacondytransformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;
import static cpw.mods.modlauncher.api.ITransformer.Target.targetMethod;

final class AnacondyWorkaroundTransformers {
    private AnacondyWorkaroundTransformers() {}

    /**
     * Rewrites the call to {@code Minecraft.getWindow()} inside {@code Options.getFullscreenVideoModeString()}
     * to instead call {@code Minecraft.getWindow$AnacondyNoInline()}, to work around an issue where this method is
     * called before the window is set, causing an NPE as the window field is then constant folded to {@code null}.
     */
    static final class OptionsGetFullscreenVideoModeStringFixer implements AnacondyTransformers.Transformer<MethodNode>, ITransformer<MethodNode> {
        @Override
        public @NonNull MethodNode transform(MethodNode methodNode, ITransformerVotingContext context) {
            // Find Minecraft#getWindow() call and replace with Minecraft#getWindow$AnacondyNoInline()
            var insns = methodNode.instructions.iterator();
            while (insns.hasNext()) {
                var insn = insns.next();
                if (!(insn instanceof MethodInsnNode methodInsn
                        && methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && methodInsn.name.equals("getWindow")
                        && methodInsn.owner.equals(Utils.MINECRAFT_CLASS_NAME)))
                    continue;

                insns.set(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        methodInsn.owner,
                        "getWindow$AnacondyNoInline",
                        methodInsn.desc,
                        methodInsn.itf
                ));
            }
            return methodNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetMethod(
                    "net/minecraft/client/Options",
                    "getFullscreenVideoModeString",
                    "()Ljava/lang/String;"
            ));
        }
    }

    /**
     * @see OptionsGetFullscreenVideoModeStringFixer
     */
    static final class MinecraftClientAddGetWindowNoInlineMethodTransformer implements AnacondyTransformers.Transformer<ClassNode>, ITransformer<ClassNode> {
        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            var noInlineMethod = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "getWindow$AnacondyNoInline",
                    "()Lcom/mojang/blaze3d/platform/Window;",
                    null,
                    null
            );

            noInlineMethod.visitCode();
            noInlineMethod.visitVarInsn(Opcodes.ALOAD, 0);
            noInlineMethod.visitFieldInsn(
                    Opcodes.GETFIELD,
                    Utils.MINECRAFT_CLASS_NAME,
                    "window",
                    "Lcom/mojang/blaze3d/platform/Window;"
            );
            noInlineMethod.visitInsn(Opcodes.ARETURN);
            noInlineMethod.visitEnd();

            classNode.methods.add(noInlineMethod);

            return classNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetClass(Utils.MINECRAFT_CLASS_NAME));
        }
    }

    /**
     * Workaround until I get AccessTransformers setup in the mod part of Anacondy.
     */
    record MakeFieldAccessibleTransformer(
            Target targetClass,
            String fieldName,
            String fieldDescriptor
    ) implements AnacondyTransformers.Transformer<ClassNode>, ITransformer<ClassNode> {
        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            for (var field : classNode.fields) {
                if (field.name.equals(fieldName) && field.desc.equals(fieldDescriptor)) {
                    // Remove private/protected access flags
                    field.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);

                    // Add public access flag
                    field.access |= Opcodes.ACC_PUBLIC;
                    break;
                }
            }
            return classNode;
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetClass);
        }
    }
}
