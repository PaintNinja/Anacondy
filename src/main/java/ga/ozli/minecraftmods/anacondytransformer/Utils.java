package ga.ozli.minecraftmods.anacondytransformer;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.ListIterator;

final class Utils {
    private Utils() {}

    static final String MINECRAFT_CLASS_NAME = "net/minecraft/client/Minecraft";

    static Handle toAsmHandle(DirectMethodHandleDesc directMethodHandleDesc) {
        var ownerTypeDescStr = toInternalName(directMethodHandleDesc.owner());
        var isFieldGetter = directMethodHandleDesc.kind() == DirectMethodHandleDesc.Kind.STATIC_GETTER;

        return new Handle(
                isFieldGetter ? Opcodes.H_GETSTATIC : Opcodes.H_INVOKESTATIC,
                ownerTypeDescStr,
                directMethodHandleDesc.methodName(),
                isFieldGetter
                        ? 'L' + toInternalName(directMethodHandleDesc.invocationType().returnType()) + ';'
                        : directMethodHandleDesc.invocationType().descriptorString(),
                directMethodHandleDesc.isOwnerInterface()
        );
    }

    static String toInternalName(ClassDesc classDesc) {
        var descStr = classDesc.descriptorString();
        return descStr.substring(1, descStr.length() - 1);
    }

    static String camelCaseToScreamingSnakeCase(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }

    static void removePreviousALoad0IfPresent(ListIterator<AbstractInsnNode> insns) {
        if (!insns.hasPrevious()) return;

        insns.previous();
        if (!insns.hasPrevious()) {
            insns.next();
            return;
        }

        var previousInsn = insns.previous();
        if (previousInsn instanceof VarInsnNode prevVarInsn
                && prevVarInsn.getOpcode() == Opcodes.ALOAD
                && prevVarInsn.var == 0) {
            insns.set(new InsnNode(Opcodes.NOP));
        }

        // Move back to the original position
        insns.next();
        insns.next();
    }

    static void removePreviousInsnsIfSingletonInstanceLoad(ListIterator<AbstractInsnNode> insns) {
        if (insns.hasPrevious()) {
            insns.previous();
            if (!insns.hasPrevious()) {
                insns.next();
                return;
            }

            if (isSingletonInstanceInsn(insns.previous())) {
                // Replace the singleton instance load instruction with a NOP as it's redundant, due to the following
                // instruction being a CONDY for the instance field access on the singleton instance.

                // Using NOP instead of removing the instruction to avoid breaking jump targets and instruction
                // indices for other transformers that may rely on them.
                insns.set(new InsnNode(Opcodes.NOP));

                // We went back two instructions, so move forward twice to be back at the original position
                insns.next();
                insns.next();
            }
        }
    }

    /** @return Whether the instruction is a load of the singleton instance, either via CONDY, field access, method call or `this` */
    private static boolean isSingletonInstanceInsn(AbstractInsnNode insn) {
        return switch (insn) {
            case LdcInsnNode ldcInsnNode when ldcInsnNode.cst instanceof ConstantDynamic constantDynamic
                    && constantDynamic.getName().endsWith("_INSTANCE") -> true;
            case FieldInsnNode prevFieldInsn when "instance".equals(prevFieldInsn.name) -> true;
            case MethodInsnNode prevMethodInsn when "getInstance".equals(prevMethodInsn.name) -> true;
            case VarInsnNode prevVarInsn when prevVarInsn.getOpcode() == Opcodes.ALOAD && prevVarInsn.var == 0 -> true;
            default -> false;
        };
    }
}
