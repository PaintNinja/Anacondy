package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.constant.ConstantDescs;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A version of {@link SingletonAccessedFieldsTransformer} intended for fields inside a different singleton class than
 * the one being transformed.
 */
record SingletonAccessedForeignFieldsTransformer(
        Set<Target> targetClasses,
        ConstantDynamic singletonAccessorCondy,
        Set<String> allowedForeignFields,
        Predicate<String> isMethodNameBlacklisted
) implements Transformer<ClassNode>, ITransformer<ClassNode> {
    SingletonAccessedForeignFieldsTransformer(Set<Target> targetClasses, ConstantDynamic singletonAccessorCondy, Set<String> allowedForeignFields) {
        this(
                targetClasses,
                singletonAccessorCondy,
                allowedForeignFields,
                Set.of(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.INIT_NAME, "close")
        );
    }

    SingletonAccessedForeignFieldsTransformer(
            Set<Target> targetClasses,
            ConstantDynamic singletonAccessorCondy,
            Set<String> allowedForeignFields,
            Set<String> blacklistedMethodNames
    ) {
        this(targetClasses, singletonAccessorCondy, allowedForeignFields, blacklistedMethodNames::contains);
    }

    @Override
    public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
        for (var methodNode : classNode.methods) {
            if (isMethodNameBlacklisted.test(methodNode.name)) continue;
            if (methodNode.name.contains("$")) continue;

            var insns = methodNode.instructions.iterator();
            while (insns.hasNext()) {
                var insn = insns.next();
                if (!(insn instanceof FieldInsnNode fieldInsn
                        && fieldInsn.getOpcode() == Opcodes.GETFIELD
                        && allowedForeignFields.contains(fieldInsn.name)
                        && fieldInsn.owner.equals(Utils.fieldDescToInternalName(singletonAccessorCondy.getDescriptor()))))
                    continue;

                Utils.removePreviousInsnsIfSingletonInstanceLoad(insns, false);

                insns.set(new LdcInsnNode(new ConstantDynamic(
                        Utils.camelCaseToScreamingSnakeCase(fieldInsn.name),
                        fieldInsn.desc,
                        AnacondyTransformers.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS,
                        new Handle(Opcodes.H_GETFIELD, fieldInsn.owner, fieldInsn.name, fieldInsn.desc, false),
                        singletonAccessorCondy
                )));

//                AnacondyTransformers.LOGGER.info("");
//                AnacondyTransformers.LOGGER.info(methodNode.name + methodNode.desc + " in " + classNode.name);
//                AnacondyTransformers.LOGGER.info(ASMAPI.methodNodeToString(methodNode));
            }
        }

        return classNode;
    }

    @Override
    public @NotNull Set<Target> targets() {
        return targetClasses;
    }
}
