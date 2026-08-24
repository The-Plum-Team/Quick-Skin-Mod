package com.quickskin.mod.mixin.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Set;

/**
 * Resource-only gate for optional third-party compatibility mixins. Resource
 * lookup is deliberate: Class.forName here can load Minecraft types before the
 * mixin transformer has had a chance to process them.
 */
public class EarsMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Use resource lookup instead of Class.forName() to avoid loading the class
        // (which would transitively load AbstractClientPlayer before mixins can transform it)
        if (mixinClassName.contains("EarsLayerRendererMixin")) {
            return classFileExists("com/unascribed/ears/EarsLayerRenderer.class");
        }
        if (mixinClassName.contains("EarsModMixin")) {
            return classFileExists("com/unascribed/ears/EarsMod.class");
        }
        // CPM targets are @Pseudo and live in this optional, fail-open config. Do not resource-gate
        // them here: on current Fabric the plugin is queried before CPM's collector resource is
        // visible, even though Mixin can resolve and transform that target later in startup.
        return true;
    }

    private static boolean classFileExists(String classFilePath) {
        return Thread.currentThread().getContextClassLoader().getResource(classFilePath) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public java.util.List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
