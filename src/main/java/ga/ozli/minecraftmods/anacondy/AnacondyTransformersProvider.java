package ga.ozli.minecraftmods.anacondy;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import ga.ozli.minecraftmods.anacondy.transformer.AnacondyTransformers;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

public record AnacondyTransformersProvider(String name) implements ITransformationService {
    @SuppressWarnings("unused") // called by the service loader
    public AnacondyTransformersProvider() {
        this("anacondytransformer");
    }

    @Override
    public void initialize(IEnvironment environment) {}

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {}

    @Override
    @SuppressWarnings("rawtypes")
    public @NotNull List<ITransformer> transformers() {
        return AnacondyTransformers.getAll();
    }
}
