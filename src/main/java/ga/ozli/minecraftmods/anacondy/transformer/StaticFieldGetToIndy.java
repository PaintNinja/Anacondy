package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;
import java.util.Set;

/**
 * Similar to {@link StaticFieldGetToCondy} but accounts for the field possibly not being initialised yet, defaulting to
 * null in that case. Indy will allow constant folding once the field is non-null, otherwise checking on each access.
 */
final class StaticFieldGetToIndy {
    private StaticFieldGetToIndy() {}

    /**
     * Similar to {@link StaticFieldGetToCondy} but accounts for fields that may not be initialised yet, defaulting to
     * null in that case. This transformer uses a dynamically resolved CallSite that intercepts the field access and
     * permanently replaces it with a constant MethodHandle once the field is non-null.
     */
    record ConstantOnceNonNull(Target targetMethod, String getStaticName)
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

    /**
     * Transformer for static fields that are "mostly constant"/rarely changed. This transformer intercepts the field
     * setters to update the associated getter's CallSite target to a constant handle, allowing for more aggressive
     * constant folding. This should be used sparingly, as frequent changes to the field forces the JVM to de-optimise
     * back to interpreted mode until the JIT re-optimises it.
     */
    record MostlyConstant(Target targetClass, String staticFieldName)
            implements Transformer<ClassNode>, ITransformer<ClassNode> {
        private static final Handle HANDLE_BSM_MOSTLY_CONSTANT_FIELD_GETTER;
        private static final Handle HANDLE_BSM_MOSTLY_CONSTANT_FIELD_SETTER;
        static {
            var bsmOwner = "ga/ozli/minecraftmods/anacondy/AnacondyBootstraps";
            var bsmDesc = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;";
            HANDLE_BSM_MOSTLY_CONSTANT_FIELD_GETTER = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    bsmOwner,
                    "mostlyConstantFieldGetter",
                    bsmDesc,
                    false
            );
            HANDLE_BSM_MOSTLY_CONSTANT_FIELD_SETTER = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    bsmOwner,
                    "mostlyConstantFieldSetter",
                    bsmDesc,
                    false
            );
        }

        @Override
        public @NonNull ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
            for (var methodNode : classNode.methods)
                transformMethod(methodNode);

            return classNode;
        }

        private void transformMethod(MethodNode methodNode) {
            for (ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator(); iterator.hasNext(); ) {
                var insn = iterator.next();
                if (!(insn instanceof FieldInsnNode fieldInsn && fieldInsn.name.equals(staticFieldName)))
                    continue;

                switch (fieldInsn.getOpcode()) {
                    case Opcodes.GETSTATIC ->
                            iterator.set(new InvokeDynamicInsnNode(
                                    fieldInsn.name,
                                    "()" + fieldInsn.desc,
                                    HANDLE_BSM_MOSTLY_CONSTANT_FIELD_GETTER,
                                    Type.getObjectType(fieldInsn.owner),
                                    new Handle(
                                            Opcodes.H_GETSTATIC,
                                            targetClass.className(),
                                            staticFieldName,
//                                            Utils.returnTypeNameFromMethodDesc(targetMethod.elementDescriptor()),
                                            fieldInsn.desc,
                                            false
                                    )
                            ));
                    case Opcodes.PUTSTATIC ->
                            iterator.set(new InvokeDynamicInsnNode(
                                    fieldInsn.name,
                                    "(" + fieldInsn.desc + ")V",
                                    HANDLE_BSM_MOSTLY_CONSTANT_FIELD_SETTER,
                                    Type.getObjectType(fieldInsn.owner),
                                    new Handle(
                                            Opcodes.H_PUTSTATIC,
                                            targetClass.className(),
                                            staticFieldName,
                                            fieldInsn.desc,
                                            false
                                    )
                            ));
                    default -> {}
                }
            }
        }

        @Override
        public @NotNull Set<Target> targets() {
            return Set.of(targetClass);
        }
    }
}
