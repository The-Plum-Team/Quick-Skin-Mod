package com.quickskin.mod.mixin.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Resource-only gate for optional third-party compatibility mixins. Resource
 * lookup is deliberate: Class.forName here can load Minecraft types before the
 * mixin transformer has had a chance to process them.
 */
public class EarsMixinPlugin implements IMixinConfigPlugin {
    private static final List<String> EARS_FIVE_MIXINS = List.of(
            "EarsFiveMixin",
            "EarsFiveBuzzMixin"
    );
    private static final List<String> CPM_MIXINS = List.of(
            "CpmModelDefinitionLoaderMixin",
            "CpmRenderDepthMixin"
    );
    private static final String CPM_SUBMIT_COLLECTOR_MIXIN = "CpmSubmitCollectorMixin";
    private static final String REPLAY_MOD_COMPAT_MIXIN = "ReplayModCompatMixin";
    private static final String REAL_CAMERA_TEXTURE_ID_MIXIN = "RealCameraTextureIdMixin";

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
        // TaCZ's target is @Pseudo, but resource-gated like the Ears targets below: an ungated @Pseudo
        // target that is absent costs a Mixin warning at every start of every client without TaCZ. The
        // resource is visible here on Forge 1.20.1, the lane official TaCZ exists for. TaCZ itself
        // loads that class only when Player Animator is installed.
        if (mixinNamed(mixinClassName, "TaczPreviewAnimationMixin")) {
            return classFileExists(
                    "com/tacz/guns/compat/playeranimator/animation/AnimationManager.class");
        }
        if (mixinClassName.contains("EarsLayerRendererMixin")) {
            return classFileExists("com/unascribed/ears/EarsLayerRenderer.class");
        }
        if (mixinClassName.contains("EarsModMixin")) {
            return classFileExists("com/unascribed/ears/EarsMod.class");
        }
        // Ears 2.x keeps its lookup in one class per port (Thermite, Ward, Buzz) instead of the
        // 1.4.x classes gated above. Those targets are @Pseudo and the mixins reference no Ears
        // types, so they follow the CPM policy below rather than a resource gate.
        if (EARS_FIVE_MIXINS.stream().anyMatch(name -> mixinNamed(mixinClassName, name))) {
            return true;
        }
        // CPM targets are @Pseudo and live in this optional, fail-open config. Do not resource-gate
        // them here: on current Fabric the plugin is queried before CPM's collector resource is
        // visible, even though Mixin can resolve and transform that target later in startup.
        if (CPM_MIXINS.stream().anyMatch(name -> mixinNamed(mixinClassName, name))
                || mixinNamed(mixinClassName, CPM_SUBMIT_COLLECTOR_MIXIN)) {
            return true;
        }
        // The ReplayMod bridge targets a vanilla packet and contains no references to ReplayMod
        // classes. It is therefore safe to transform unconditionally; its handler no-ops unless
        // the active connection is ReplayMod's fake playback connection.
        if (mixinNamed(mixinClassName, REPLAY_MOD_COMPAT_MIXIN)) {
            return true;
        }
        // The Real Camera target is @Pseudo, so Mixin already skips it when that mod is absent; a
        // resource gate could only switch the shim off by mistake (see the CPM note above).
        if (mixinNamed(mixinClassName, REAL_CAMERA_TEXTURE_ID_MIXIN)) {
            return true;
        }
        return false;
    }

    private static boolean mixinNamed(String mixinClassName, String simpleName) {
        return mixinClassName.equals(simpleName) || mixinClassName.endsWith("." + simpleName);
    }

    private static boolean classFileExists(String classFilePath) {
        return Thread.currentThread().getContextClassLoader().getResource(classFilePath) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
