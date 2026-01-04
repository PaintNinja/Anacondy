package ga.ozli.minecraftmods.anacondy.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.jetbrains.annotations.NotNull;

sealed interface Transformer<T> extends ITransformer<T>
        permits ClassToRecordTransformer, StaticFieldGetToIndy, DebugEntrySystemSpecsTransformer, MinecraftClientFieldCopiesTransformer, SingletonAccessedFieldsTransformer, SingletonAccessedForeignFieldsTransformer, StaticFieldGetToCondy, Workarounds.MakeFieldAccessible, Workarounds.MinecraftClientAddGetWindowNoInlineMethodTransformer, Workarounds.OptionsGetFullscreenVideoModeStringFixer {
    @Override
    default @NotNull TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }
}
