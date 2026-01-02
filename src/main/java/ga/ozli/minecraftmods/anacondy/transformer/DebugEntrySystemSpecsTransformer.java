package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * Replaces the call to {@code System.getProperty("java.version")} inside DebugEntrySystemSpecs#display with a constant
 * string that is the result of that call at transformation time.
 */
public final class DebugEntrySystemSpecsTransformer implements Transformer<MethodNode>, ITransformer<MethodNode> {
    @Override
    public @NonNull MethodNode transform(MethodNode methodNode, ITransformerVotingContext context) {
        for (int i = 1; i < methodNode.instructions.size(); i++) {
            var insn = methodNode.instructions.get(i);
            if (!(insn instanceof MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKESTATIC
                    && methodInsn.owner.equals("java/lang/System")
                    && methodInsn.name.equals("getProperty")
                    && methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")))
                continue;

            var previousInsn = insn.getPrevious();
            while (!(previousInsn instanceof LdcInsnNode ldcInsnNode && ldcInsnNode.cst.equals("java.version")))
                previousInsn = previousInsn.getPrevious();

            methodNode.instructions.set(previousInsn, new InsnNode(Opcodes.NOP));
            methodNode.instructions.set(insn, new LdcInsnNode(System.getProperty("java.version")));
        }

        return methodNode;
    }

    @Override
    public @NotNull Set<Target> targets() {
        return Set.of(Target.targetMethod(
                "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs",
                "display",
                "(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        ));
    }
}
