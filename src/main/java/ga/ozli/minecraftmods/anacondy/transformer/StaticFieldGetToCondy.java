package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Locale;
import java.util.Set;

/**
 * Rewrites {@code GETSTATIC singletonInstance} inside target methods to instead load a ConstantDynamic that
 * resolves to the same field, allowing the JVM to perform constant folding optimisations on it.
 *
 * @param targetMethod  the method containing the static field get instruction to be transformed
 * @param getStaticName the name of the static field being accessed
 */
record StaticFieldGetToCondy(Target targetMethod, String getStaticName, String condyName)
        implements Transformer<MethodNode>, ITransformer<MethodNode> {
    /**
     * Assumes the field name is simply a camelCase version of the method name without the "get" prefix.
     * <p>The CONDY name will be the screaming snake case version of that.</p>
     */
    StaticFieldGetToCondy(Target targetMethod) {
        this(
                targetMethod,
                targetMethod.elementName().substring(3, 4).toLowerCase(Locale.ROOT)
                        + targetMethod.elementName().substring(4)
        );
    }

    StaticFieldGetToCondy(Target targetMethod, String getStaticName) {
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
                    AnacondyTransformers.HANDLE_BSM_INVOKE_NON_NULL,
                    new Handle(Opcodes.H_GETSTATIC, fieldInsn.owner, fieldInsn.name, fieldInsn.desc, false)
            )));
            AnacondyTransformers.TOTAL_REWRITES.getAndIncrement();
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
