package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.constant.ConstantDescs;
import java.util.Set;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;

// Todo: Generalise this to work for any specified singleton class, not just Minecraft
/**
 * Rewrites {@code GETFIELD minecraft} and similar instructions to instead call {@code Minecraft.getInstance()},
 * expanding the benefit of constant folding optimisations via the ConstantDynamic in that method made by
 * {@link StaticFieldGetToCondy}.
 */
final class MinecraftClientFieldCopiesTransformer implements Transformer<ClassNode>, ITransformer<ClassNode> {
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
                            AnacondyTransformers.TARGET_MC_GET_INSTANCE_METHOD.className(),
                            AnacondyTransformers.TARGET_MC_GET_INSTANCE_METHOD.elementName(),
                            AnacondyTransformers.TARGET_MC_GET_INSTANCE_METHOD.elementDescriptor(),
                            false
                    ));
                    AnacondyTransformers.TOTAL_REWRITES.getAndIncrement();
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
