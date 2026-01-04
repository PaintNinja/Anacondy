package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ListIterator;
import java.util.Set;

// Todo: Instead of checking for null each access, intercept setters so that they set the new indy CallSite target
/**
 * Similar to {@link StaticFieldGetToCondy} but accounts for the field possibly not being initialised yet, defaulting to
 * null in that case. Indy will allow constant folding once the field is non-null, otherwise checking on each access.
 */
record StaticFieldGetToIndy(Target targetMethod, String getStaticName)
        implements Transformer<MethodNode>, ITransformer<MethodNode> {
    /**
     * {@link ga.ozli.minecraftmods.anacondy.AnacondyBootstraps#constantFoldWhenNonNull(java.lang.invoke.MethodHandles.Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.Class, java.lang.invoke.MethodHandle)}
     */
    private static final Handle HANDLE_BSM_CONSTANT_FOLD_WHEN_NON_NULL = new Handle(
            Opcodes.H_INVOKESTATIC,
            "ga/ozli/minecraftmods/anacondy/AnacondyBootstraps",
            "constantFoldWhenNonNull",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;",
            false
    );

    @Override
    public @NotNull MethodNode transform(MethodNode methodNode, ITransformerVotingContext context) {
        for (ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator(); iterator.hasNext(); ) {
            var insn = iterator.next();
            if (!(insn instanceof FieldInsnNode fieldInsn
                    && fieldInsn.getOpcode() == Opcodes.GETSTATIC
                    && fieldInsn.name.equals(getStaticName)))
                continue;

            iterator.set(new InvokeDynamicInsnNode(
                    fieldInsn.name,
                    "()" + fieldInsn.desc,
                    HANDLE_BSM_CONSTANT_FOLD_WHEN_NON_NULL,
                    Type.getObjectType(fieldInsn.owner),
                    new Handle(
                            Opcodes.H_GETSTATIC,
                            targetMethod.className(),
                            getStaticName,
                            Utils.returnTypeNameFromMethodDesc(targetMethod.elementDescriptor()),
                            false
                    )
            ));
        }

        return methodNode;
    }

    @Override
    public @NotNull Set<Target> targets() {
        return Set.of(targetMethod);
    }
}
