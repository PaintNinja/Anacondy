import cpw.mods.modlauncher.api.ITransformationService;
import ga.ozli.minecraftmods.anacondy.AnacondyTransformersProvider;
import net.minecraftforge.forgespi.locating.IModLocator;

module ga.ozli.minecraftmods.anacondy {
    requires cpw.mods.modlauncher;
    requires cpw.mods.securejarhandler;

    requires net.minecraftforge.coremod;
    requires net.minecraftforge.fmlloader;
    requires net.minecraftforge.forgespi;

    requires static org.jetbrains.annotations;
    requires static org.jspecify;

    requires org.objectweb.asm;
    requires org.objectweb.asm.tree;
    requires org.objectweb.asm.tree.analysis;
    requires org.objectweb.asm.util;

    requires org.slf4j;

    exports ga.ozli.minecraftmods.anacondy;

    // remember to update the legacy META-INF/services files as well
    provides ITransformationService with AnacondyTransformersProvider;
    provides IModLocator with ga.ozli.minecraftmods.anacondy.AnacondyLocator;
}
