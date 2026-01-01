package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.lang.constant.ConstantDescs;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// Todo: Look into supporting chaining of singleton accessed fields from other classes
//       e.g.: Minecraft.levelRenderer.levelRenderState.entityRenderStates or this.minecraft.options.getCameraType()
/**
 * Rewrites {@code GETFIELD this.finalField} instructions inside the singleton classes to instead use ConstantDynamics
 * that access the final fields via the singleton instance, effectively turning them into trusted final fields.
 */
record SingletonAccessedFieldsTransformer(
        Target targetClass,
        ConstantDynamic singletonAccessorCondy,
        Predicate<String> isBlacklisted
) implements Transformer<ClassNode>, ITransformer<ClassNode> {
    SingletonAccessedFieldsTransformer(Target targetClass, ConstantDynamic singletonAccessorCondy) {
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

        Set<String> nullableFieldNames = instanceFinalFields.stream()
                .filter(fieldNode -> fieldNode.visibleTypeAnnotations != null)
                .filter(fieldNode -> fieldNode.visibleTypeAnnotations.stream()
                        .anyMatch(typeAnno -> typeAnno.desc.endsWith("/Nullable;")))
                .map(fieldNode -> fieldNode.name)
                .collect(Collectors.toUnmodifiableSet());

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
                        !nullableFieldNames.contains(fieldInsn.name)
                                ? AnacondyTransformers.HANDLE_BSM_INVOKE_NON_NULL_WITH_ARGS
                                : AnacondyTransformers.HANDLE_BSM_INVOKE,
                        new Handle(
                                Opcodes.H_GETFIELD,
                                targetClass.className(),
                                fieldInsn.name,
                                fieldInsn.desc,
                                false
                        ),
                        singletonAccessorCondy
                )));
                AnacondyTransformers.TOTAL_REWRITES.getAndIncrement();
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
