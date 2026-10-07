package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.util.FilePickerFiles;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** File chooser rendered by Minecraft, without desktop processes or an Android SDK dependency. */
@Environment(EnvType.CLIENT)
public final class MobileFilePickerScreen extends Screen {
    private static final Path STORAGE = Path.of("/storage/emulated/0");
    private static final Path DOWNLOADS = STORAGE.resolve("Download");
    private static final int MAX_SELECTED_FILES = 256;
    private static final int ACCESS_HINT_COLOR = 0xFFCC66;
    private final Screen parent;
    private final Set<String> extensions;
    private final boolean multiple;
    private final Consumer<List<Path>> callback;
    private final Runnable onClosed;
    private final Set<Path> selected = new LinkedHashSet<>();
    private List<FilePickerFiles.Entry> entries = List.of();
    private Path directory = DOWNLOADS;
    private Component status = Component.empty();
    private int page;
    private int rows;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private long generation;
    private boolean loading;
    private boolean closed;

    public MobileFilePickerScreen(Screen parent, String title, Set<String> extensions,
                                  boolean multiple, Consumer<List<Path>> callback, Runnable onClosed) {
        super(Component.literal(title));
        this.parent = parent;
        this.extensions = Set.copyOf(extensions);
        this.multiple = multiple;
        this.callback = callback;
        this.onClosed = onClosed;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(440, width - 20);
        panelHeight = Math.min(300, height - 20);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 136) / 22);
        page = Math.min(page, Math.max(0, (entries.size() - 1) / rows));
        buildFileWidgets();
        if (generation == 0) navigate(directory);
    }

    private Button button(int x, int y, int w, Component text, Runnable action) {
        return addRenderableWidget(ButtonFactory.createStyled(x, y, w, 20, text, ignored -> action.run()));
    }

    private void buildFileWidgets() {
        clearWidgets();
        int x = panelX + 10;
        int w = panelWidth - 20;
        int y = panelY + 42;
        int shortcutWidth = (w - 9) / 4;
        button(x, y, shortcutWidth, label("downloads"), () -> navigate(DOWNLOADS));
        button(x + shortcutWidth + 3, y, shortcutWidth, label("storage"), () -> navigate(STORAGE));
        button(x + 2 * (shortcutWidth + 3), y, shortcutWidth,
                label("game").copy().withStyle(style -> style.withColor(ACCESS_HINT_COLOR)),
                () -> navigate(PlatformHelper.getGameDirectory()));
        Button up = button(x + 3 * (shortcutWidth + 3), y, shortcutWidth, label("up"),
                () -> navigate(directory.getParent()));
        up.active = !loading && directory.getParent() != null;
        int start = page * rows;
        for (int row = 0; row < rows && start + row < entries.size(); row++) {
            FilePickerFiles.Entry entry = entries.get(start + row);
            String name = (entry.directory() ? "[+] " : selected.contains(entry.path()) ? "[x] " : "")
                    + entry.path().getFileName();
            // A long file name must not overflow into the preview or adjacent controls.
            name = font.plainSubstrByWidth(name, w - 12);
            Button item = button(x, y + 25 + row * 22, w, Component.literal(name), () -> choose(entry));
            item.active = !loading;
        }
        int navigationY = panelY + panelHeight - 64;
        Button previous = button(x, navigationY, 44, Component.literal("<"), () -> {
            page--;
            buildFileWidgets();
        });
        previous.active = !loading && page > 0;
        Button next = button(x + w - 44, navigationY, 44, Component.literal(">"), () -> {
            page++;
            buildFileWidgets();
        });
        next.active = !loading && (page + 1) * rows < entries.size();
        int bottom = panelY + panelHeight - 26;
        button(x, bottom, (w - 4) / 2, CommonComponents.GUI_CANCEL, this::onClose);
        Button confirm = button(x + (w + 4) / 2, bottom, (w - 4) / 2,
                Component.translatable("quickskin.file_picker.import", selected.size()), this::complete);
        confirm.active = multiple && !loading && !selected.isEmpty();
    }

    private static Component label(String key) {
        return Component.translatable("quickskin.file_picker." + key);
    }

    private void navigate(Path target) {
        if (target == null || closed || loading) return;
        Path requested = target.toAbsolutePath().normalize();
        long request = ++generation;
        loading = true;
        status = label("loading");
        buildFileWidgets();
        ClientIoExecutor.supplyAsync(() -> {
            try {
                return FilePickerFiles.list(requested, extensions);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((files, error) -> minecraft.execute(() -> {
            if (closed || request != generation || GuiCompat.currentScreen() != this) return;
            loading = false;
            if (error == null) {
                directory = requested;
                entries = files;
                page = 0;
                status = files.isEmpty() ? label("empty")
                        : label("access_hint").copy().withStyle(style -> style.withUnderlined(true));
            } else {
                status = label("unreadable");
                QuickSkinInfo.LOGGER.warn("Unable to list the mobile file picker directory {}", requested, error);
            }
            buildFileWidgets();
        }));
    }

    private void choose(FilePickerFiles.Entry entry) {
        if (entry.directory()) {
            navigate(entry.path());
        } else if (!multiple) {
            selected.add(entry.path());
            complete();
        } else {
            if (!selected.remove(entry.path())) {
                if (selected.size() == MAX_SELECTED_FILES) {
                    status = Component.translatable("quickskin.file_picker.limit", MAX_SELECTED_FILES);
                    return;
                }
                selected.add(entry.path());
            }
            buildFileWidgets();
        }
    }

    private void complete() {
        if (closed || selected.isEmpty()) return;
        List<Path> files = new ArrayList<>(selected);
        // Restore first: import may itself open a cape editor or a rename prompt.
        GuiCompat.openScreen(parent);
        callback.accept(List.copyOf(files));
    }

    @Override
    public void onClose() {
        if (!closed) GuiCompat.openScreen(parent);
    }

    @Override
    public void removed() {
        if (!closed) {
            closed = true;
            generation++;
            onClosed.run();
        }
        super.removed();
    }

    //? if >=1.21 && <26.1 {
        //? if <1.21.2 {
    @Override
    public void renderBlurredBackground(float partialTick) {
        //?} else if <1.21.6 {
    @Override
    protected void renderBlurredBackground() {
        //?} else {
    @Override
    protected void renderBlurredBackground(GuiGraphics graphics) {
        //?}
        // The file browser paints its own opaque background.
    }
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
        //?}
    //?}
    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {}
    //?}

    @Override
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF202028);
        Component visibleStatus = Component.literal(font.plainSubstrByWidth(status.getString(), panelWidth - 20))
                .withStyle(status.getStyle());
        //? if <26.1 {
        graphics.drawCenteredString(font, title, width / 2, panelY + 8, 0xFFFFFFFF);
        graphics.drawString(font, font.plainSubstrByWidth(directory.toString(), panelWidth - 20),
                panelX + 10, panelY + 26, 0xFFCCCCCC);
        graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (entries.size() + rows - 1) / rows),
                width / 2, panelY + panelHeight - 58, 0xFFFFFFFF);
        graphics.drawCenteredString(font, visibleStatus,
                width / 2, panelY + panelHeight - 39, 0xFF000000 | ACCESS_HINT_COLOR);
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        graphics.centeredText(font, title, width / 2, panelY + 8, 0xFFFFFFFF);
        graphics.text(font, font.plainSubstrByWidth(directory.toString(), panelWidth - 20),
                panelX + 10, panelY + 26, 0xFFCCCCCC);
        graphics.centeredText(font, (page + 1) + " / " + Math.max(1, (entries.size() + rows - 1) / rows),
                width / 2, panelY + panelHeight - 58, 0xFFFFFFFF);
        graphics.centeredText(font, visibleStatus,
                width / 2, panelY + panelHeight - 39, 0xFF000000 | ACCESS_HINT_COLOR);
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        //?}
    }
}
