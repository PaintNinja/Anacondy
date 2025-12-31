package ga.ozli.minecraftmods.anacondytransformer;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.ForgeFeature;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileLoadingException;
import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public final class AnacondyLocator implements IModLocator {
    @Override
    public List<ModFileOrException> scanMods() {
        var anacondyJarPath = getPathFromResource(
                getClass().getClassLoader(),
                AnacondyLocator.class.getName().replace('.', '/') + ".class"
        );

        if (anacondyJarPath == null || Files.isDirectory(anacondyJarPath))
            return List.of();

        return List.of(createMod(anacondyJarPath));
    }

    private ModFileOrException createMod(Path path) {
        SecureJar sj;
        try {
            sj = SecureJar.from(jar -> JarMetadata.fromFileName(jar.getPrimaryPath(), Set.of(), List.of()), path);
            Objects.requireNonNull(sj);
        } catch (Throwable t) {
            return new IModLocator.ModFileOrException(null, new ModFileLoadingException("Failed to create secure jar for AnacondyMod - " + t.getMessage()));
        }

        return new IModLocator.ModFileOrException(new ModFile(sj, this, modJar -> new IModFileInfo() {
            @Override
            public List<IModInfo> getMods() {
                return List.of();
            }

            @Override
            public List<LanguageSpec> requiredLanguageLoaders() {
                return List.of();
            }

            @Override
            public boolean showAsResourcePack() {
                return false;
            }

            @Override
            public Map<String, Object> getFileProperties() {
                return Map.of();
            }

            @Override
            public String getLicense() {
                return "MIT";
            }

            @Override
            public String moduleName() {
                return "anacondy";
            }

            @Override
            public String versionString() {
                return "1.0.0";
            }

            @Override
            public List<String> usesServices() {
                return List.of();
            }

            @Override
            public IModFile getFile() {
                return null;
            }

            @Override
            public IConfigurable getConfig() {
                return null;
            }
        }), null);
    }

    private static Path getPathFromResource(ClassLoader cl, String resource) {
        var url = cl.getResource(resource);
        if (url == null)
            return null;
        return getPath(url, resource);
    }

    private static Path getPath(URL url, String resource) {
        var str = url.toString();
        int len = resource.length();
        if ("jar".equalsIgnoreCase(url.getProtocol())) {
            str = url.getFile();
            len += 2;
        }
        str = str.substring(0, str.length() - len);
        return Path.of(URI.create(str));
    }

    @Override
    public String name() {
        return "anacondylocator";
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {}

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    @Override
    public boolean isValid(IModFile modFile) {
        return true;
    }
}
