package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.api.ClientNetworkActions;
import com.quickskin.mod.client.api.ClientNetworkApi;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.DialogFrame;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.gui.widget.ConfirmButton;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.SkinService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerNicknameRepository;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Where an admin gives players a skin for now, which others see instead of theirs: the players
 * online on the left, picked by clicking them, and on the right the skins to give, the admin's
 * own catalog or the server's skin pool. Clicking a skin gives it to the picked players; Random
 * gives each a pool skin, Shuffle swaps their own skins and Restore takes the given skins back.
 * A catalog skin's {@code +} adds it to the pool, a pool skin's {@code ×} removes it. The server
 * does the work and answers; each answer shows as a toast.
 */
@Environment(EnvType.CLIENT)
public class PlayerSkinsScreen extends Screen {
    private static final String[] ICON_PLAYERS = {
            ".......##", "..##..###", ".####.###", ".####..#.", "..##.####",
            ".........", ".######..", "########.", "########."};
    private static final int MAX_PANEL_WIDTH = 420;
    private static final int MAX_PANEL_HEIGHT = 260;
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 18;
    private static final int FACE = 14;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int LIST_BACK = 0xFF1C1D1F;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_PICKED = 0xFF1F3A6E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFA0A0A0;
    private static final int ASSIGNED_MARK = 0xFFFFD040;
    private static final int TOAST_GAP = 4;

    private final Screen parent;
    private final Set<UUID> picked = new LinkedHashSet<>();
    private final List<ErrorToast> toasts = new ArrayList<>();
    private List<AssetMetadata> catalog = List.of();
    private boolean showPool;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int playersX;
    private int playersWidth;
    private int skinsX;
    private int skinsWidth;
    private int playersScroll;
    private int skinsScroll;
    private ConfirmButton allButton;
    private ConfirmButton catalogTab;
    private ConfirmButton poolTab;
    /** The latest answer already shown: answers that came before the panel opened are not shown. */
    private int answerShown;

    public PlayerSkinsScreen(Screen parent) {
        super(Component.translatable("quickskin.screen.player_skins.title"));
        this.parent = parent;
        this.answerShown = ClientNetworkApi.actions().skinAdminAnswerSequence();
    }

    @Override
    protected void init() {
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        super.init();
        catalog = new ArrayList<>();
        for (AssetMetadata metadata : LocalAssetManager.getInstance().getAllSkins()) {
            if (!metadata.isCpmModel()) catalog.add(metadata);
        }

        panelWidth = Math.min(MAX_PANEL_WIDTH, this.width - 2 * MARGIN);
        panelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - 2 * MARGIN);
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
        int bodyTop = DialogFrame.bodyTop(panelY) + MARGIN;
        int buttonsY = panelY + panelHeight - DialogFrame.LIP_HEIGHT - 2 - MARGIN - BUTTON_HEIGHT;
        listTop = bodyTop + BUTTON_HEIGHT + 4;
        listBottom = buttonsY - MARGIN;
        int inner = panelWidth - 3 * MARGIN;
        playersWidth = inner * 2 / 5;
        skinsWidth = inner - playersWidth;
        playersX = panelX + MARGIN;
        skinsX = playersX + playersWidth + MARGIN;

        allButton = this.addRenderableWidget(DialogFrame.button(
                playersX + playersWidth - 56, bodyTop, 56, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.all"), button -> togglePickAll()));
        int tabWidth = (skinsWidth - BUTTON_GAP) / 2;
        catalogTab = this.addRenderableWidget(DialogFrame.button(
                skinsX, bodyTop, tabWidth, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.your_skins"), button -> setShowPool(false)));
        poolTab = this.addRenderableWidget(DialogFrame.button(
                skinsX + tabWidth + BUTTON_GAP, bodyTop, skinsWidth - tabWidth - BUTTON_GAP, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.pool"), button -> setShowPool(true)));

        int buttonWidth = (panelWidth - 2 * MARGIN - 3 * BUTTON_GAP) / 4;
        int x = panelX + MARGIN;
        this.addRenderableWidget(DialogFrame.button(x, buttonsY, buttonWidth, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.random"),
                button -> request(ClientNetworkActions.SkinAdminRequest.RANDOM, "")));
        x += buttonWidth + BUTTON_GAP;
        this.addRenderableWidget(DialogFrame.button(x, buttonsY, buttonWidth, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.shuffle"),
                button -> request(ClientNetworkActions.SkinAdminRequest.SHUFFLE, "")));
        x += buttonWidth + BUTTON_GAP;
        this.addRenderableWidget(DialogFrame.button(x, buttonsY, buttonWidth, BUTTON_HEIGHT,
                Component.translatable("quickskin.player_skins.restore"),
                button -> request(ClientNetworkActions.SkinAdminRequest.RESET, "")));
        x += buttonWidth + BUTTON_GAP;
        this.addRenderableWidget(DialogFrame.button(x, buttonsY, panelX + panelWidth - MARGIN - x, BUTTON_HEIGHT,
                CommonComponents.GUI_DONE, button -> this.onClose()));
        setShowPool(showPool);
    }

    private void setShowPool(boolean pool) {
        showPool = pool;
        skinsScroll = 0;
        catalogTab.setSelected(!pool);
        poolTab.setSelected(pool);
    }

    // ----- What the admin does -----

    private void togglePickAll() {
        List<UUID> online = onlinePlayers().stream().map(PlayerRow::id).toList();
        if (picked.containsAll(online)) {
            picked.clear();
        } else {
            picked.addAll(online);
        }
    }

    private boolean needsPicked() {
        picked.retainAll(onlinePlayers().stream().map(PlayerRow::id).toList());
        if (!picked.isEmpty()) return true;
        toast(Component.translatable("quickskin.player_skins.pick_first"), ErrorToast.Kind.INFO);
        return false;
    }

    private void request(ClientNetworkActions.SkinAdminRequest request, String poolSkin) {
        if (request != ClientNetworkActions.SkinAdminRequest.REMOVE_FROM_POOL && !needsPicked()) return;
        if (!ClientNetworkApi.actions().skinAdminAction(request, List.copyOf(picked), poolSkin)) {
            toast(Component.translatable("quickskin.player_skins.unavailable"), ErrorToast.Kind.ERROR);
        }
    }

    private void giveCatalogSkin(AssetMetadata skin) {
        if (!needsPicked()) return;
        if (!ClientNetworkApi.actions().giveLocalSkin(List.copyOf(picked), skin.hash(), modelOf(skin))) {
            toast(Component.translatable("quickskin.player_skins.unavailable"), ErrorToast.Kind.ERROR);
        }
    }

    private void addToPool(AssetMetadata skin) {
        if (!ClientNetworkApi.actions().addLocalSkinToPool(skin.friendlyName(), skin.hash(), modelOf(skin))) {
            toast(Component.translatable("quickskin.player_skins.unavailable"), ErrorToast.Kind.ERROR);
        }
    }

    private static String modelOf(AssetMetadata skin) {
        String preference = LocalAssetManager.getInstance().getSkinModelPreference(skin.hash());
        if ("slim".equals(preference) || "classic".equals(preference)) return preference;
        return "slim".equals(skin.skinModel()) ? "slim" : "classic";
    }

    /** Shows each new answer of the server once, as a toast that says what happened. */
    private void pollAnswers() {
        for (ClientNetworkActions.SkinAdminAnswer answer : ClientNetworkApi.actions().skinAdminAnswersAfter(answerShown)) {
            answerShown = answer.sequence();
            showAnswer(answer);
        }
    }

    private void showAnswer(ClientNetworkActions.SkinAdminAnswer answer) {
        String what = switch (answer.kind()) {
            case GIVE -> "give";
            case RANDOM -> "random";
            case SHUFFLE -> "shuffle";
            case RESTORE -> "restore";
            case ADD_TO_POOL -> "pool_add";
            case REMOVE_FROM_POOL -> "pool_remove";
        };
        switch (answer.outcome()) {
            case DONE -> toast(Component.translatable("quickskin.player_skins.done." + what, answer.count()),
                    ErrorToast.Kind.SUCCESS);
            case NOT_ALLOWED -> toast(Component.translatable("quickskin.player_skins.not_allowed"), ErrorToast.Kind.ERROR);
            case MISSING_TEXTURE -> toast(Component.translatable("quickskin.player_skins.missing"), ErrorToast.Kind.ERROR);
            case POOL_EMPTY -> toast(Component.translatable("quickskin.player_skins.pool_empty"), ErrorToast.Kind.ERROR);
            case POOL_FULL -> toast(Component.translatable("quickskin.player_skins.pool_full"), ErrorToast.Kind.ERROR);
            case NAME_TAKEN -> toast(Component.translatable("quickskin.player_skins.in_pool"), ErrorToast.Kind.INFO);
            case NO_ROOM -> toast(Component.translatable("quickskin.player_skins.no_room"), ErrorToast.Kind.ERROR);
            case INVALID -> toast(Component.translatable("quickskin.player_skins.refused"), ErrorToast.Kind.ERROR);
            case NOTHING -> toast(Component.translatable("quickskin.player_skins.nothing." + what), ErrorToast.Kind.INFO);
            case NO_ANSWER -> toast(Component.translatable("quickskin.player_skins.no_answer"), ErrorToast.Kind.ERROR);
        }
    }

    private void toast(Component message, ErrorToast.Kind kind) {
        toasts.add(new ErrorToast(message, kind));
    }

    // ----- What the lists show -----

    private record PlayerRow(UUID id, String name, String nickname, PlayerInfo info) {
    }

    private List<PlayerRow> onlinePlayers() {
        List<PlayerRow> rows = new ArrayList<>();
        ClientPacketListener connection = this.minecraft != null ? this.minecraft.getConnection() : null;
        if (connection == null) return rows;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            //? if <1.21.9 {
            UUID id = info.getProfile().getId();
            String name = info.getProfile().getName();
            //?} else {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            //?}
            if (id == null || name == null) continue;
            String nickname = PlayerNicknameRepository.getInstance().nickname(id);
            rows.add(new PlayerRow(id, name, nickname != null ? nickname : "", info));
        }
        rows.sort(Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT)));
        return rows;
    }

    @Nullable
    private ClientNetworkActions.SkinAdminView view() {
        return ClientNetworkApi.actions().skinAdminView();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private int skinRowCount() {
        if (!showPool) return catalog.size();
        ClientNetworkActions.SkinAdminView view = view();
        return view != null ? view.pool().size() : 0;
    }

    // ----- Drawing -----

    @Override
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        pollAnswers();
        if (this.parent != null) {
            BlurHandler.renderParent(this.parent, graphics, partialTicks);
        }
        //? if <1.21.6 {
        graphics.flush();
        //?}
        BlurHandler.renderBlur(graphics);
        graphics.fill(0, 0, this.width, this.height, 0x60000000);
        DialogFrame.draw(graphics, this.font, panelX, panelY, panelWidth, panelHeight,
                DialogFrame.Accent.EDIT, ICON_PLAYERS, this.title);

        int labelY = DialogFrame.bodyTop(panelY) + MARGIN + 6;
        List<PlayerRow> players = onlinePlayers();
        picked.retainAll(players.stream().map(PlayerRow::id).toList());
        UiText.draw(graphics, this.font, Component.translatable("quickskin.player_skins.players", picked.size(),
                players.size()), playersX, labelY, TEXT);
        allButton.setMessage(Component.translatable(!players.isEmpty() && picked.size() == players.size()
                ? "quickskin.player_skins.none" : "quickskin.player_skins.all"));
        ClientNetworkActions.SkinAdminView view = view();
        poolTab.setMessage(Component.translatable("quickskin.player_skins.pool_count",
                view != null ? view.pool().size() : 0));

        renderPlayers(graphics, players, view, mouseX, mouseY);
        renderSkins(graphics, view, mouseX, mouseY);

        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        //?}
        renderToasts(graphics);
    }

    //? if <26.1 {
    private void renderPlayers(GuiGraphics graphics, List<PlayerRow> players,
    //?} else {
    private void renderPlayers(GuiGraphicsExtractor graphics, List<PlayerRow> players,
    //?}
                               @Nullable ClientNetworkActions.SkinAdminView view, int mouseX, int mouseY) {
        graphics.fill(playersX, listTop, playersX + playersWidth, listBottom, LIST_BACK);
        playersScroll = clampScroll(playersScroll, players.size());
        graphics.enableScissor(playersX, listTop, playersX + playersWidth, listBottom);
        for (int i = 0; i < visibleRows() + 1 && i + playersScroll < players.size(); i++) {
            PlayerRow row = players.get(i + playersScroll);
            int y = listTop + i * ROW_HEIGHT;
            boolean isPicked = picked.contains(row.id());
            if (isPicked) {
                graphics.fill(playersX, y, playersX + playersWidth, y + ROW_HEIGHT, ROW_PICKED);
            } else if (isOver(mouseX, mouseY, playersX, y, playersWidth, ROW_HEIGHT) && mouseY < listBottom) {
                graphics.fill(playersX, y, playersX + playersWidth, y + ROW_HEIGHT, ROW_HOVER);
            }
            drawFace(graphics, playerSkin(row.info()), playersX + 2, y + 2);
            int textX = playersX + 4 + FACE + 4;
            int textRoom = playersX + playersWidth - textX - 10;
            String label = row.nickname().isEmpty() ? row.name() : row.name() + " (" + row.nickname() + ")";
            UiText.draw(graphics, this.font, fit(label, textRoom), textX, y + 5,
                    isPicked ? TEXT : row.nickname().isEmpty() ? TEXT : TEXT_DIM);
            if (view != null && view.assigned().contains(row.id())) {
                // Wears a skin an admin gave
                int markX = playersX + playersWidth - 7;
                graphics.fill(markX, y + 7, markX + 4, y + 11, ASSIGNED_MARK);
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics, playersX + playersWidth - 2, players.size(), playersScroll);
    }

    //? if <26.1 {
    private void renderSkins(GuiGraphics graphics, @Nullable ClientNetworkActions.SkinAdminView view,
    //?} else {
    private void renderSkins(GuiGraphicsExtractor graphics, @Nullable ClientNetworkActions.SkinAdminView view,
    //?}
                             int mouseX, int mouseY) {
        graphics.fill(skinsX, listTop, skinsX + skinsWidth, listBottom, LIST_BACK);
        int count = skinRowCount();
        skinsScroll = clampScroll(skinsScroll, count);
        if (count == 0) {
            UiText.drawCentered(graphics, this.font, Component.translatable(showPool
                            ? "quickskin.player_skins.pool_none" : "quickskin.player_skins.catalog_none"),
                    skinsX + skinsWidth / 2, (listTop + listBottom) / 2 - 4, TEXT_DIM);
            return;
        }
        graphics.enableScissor(skinsX, listTop, skinsX + skinsWidth, listBottom);
        for (int i = 0; i < visibleRows() + 1 && i + skinsScroll < count; i++) {
            int index = i + skinsScroll;
            int y = listTop + i * ROW_HEIGHT;
            boolean over = isOver(mouseX, mouseY, skinsX, y, skinsWidth, ROW_HEIGHT) && mouseY < listBottom;
            boolean overAction = over && isOverAction(mouseX, mouseY, y);
            if (over) {
                graphics.fill(skinsX, y, skinsX + skinsWidth, y + ROW_HEIGHT, ROW_HOVER);
            }
            String name;
            if (showPool) {
                ClientNetworkActions.SkinAdminView.PoolSkin skin = view.pool().get(index);
                drawFace(graphics, SkinService.getInstance().loadLocalSkin(skin.contentId()), skinsX + 2, y + 2);
                name = skin.name();
            } else {
                AssetMetadata skin = catalog.get(index);
                drawFace(graphics, LocalAssetManager.getInstance().getTextureLocation(skin.hash(), TextureQuality.PREVIEW),
                        skinsX + 2, y + 2);
                name = skin.friendlyName();
            }
            int textX = skinsX + 4 + FACE + 4;
            UiText.draw(graphics, this.font, fit(name, actionX() - textX - 4), textX, y + 5, TEXT);
            // A catalog skin can join the pool, a pool skin can leave it
            String action = showPool ? "×" : "+";
            int ax = actionX();
            graphics.fill(ax, y + 3, ax + 12, y + 15, overAction ? 0xFF4080FF : 0xFF3A3B3F);
            UiText.drawCentered(graphics, this.font, action, ax + 6, y + 5, TEXT);
        }
        graphics.disableScissor();
        drawScrollbar(graphics, skinsX + skinsWidth - 2, count, skinsScroll);
    }

    private int actionX() {
        return skinsX + skinsWidth - 18;
    }

    /** Whether the mouse is on the drawn {@code +} or {@code ×} of the row starting at {@code rowY}. */
    private boolean isOverAction(double mouseX, double mouseY, int rowY) {
        return isOver(mouseX, mouseY, actionX(), rowY + 3, 12, 12);
    }

    //? if <26.1 {
    private void drawFace(GuiGraphics graphics,
    //?} else {
    private void drawFace(GuiGraphicsExtractor graphics,
    //?}
                          //? if <1.21.11 {
                          @Nullable ResourceLocation texture,
                          //?} else {
                          @Nullable Identifier texture,
                          //?}
                          int x, int y) {
        graphics.fill(x, y, x + FACE, y + FACE, 0xFF2A2B2E);
        if (texture == null) return;
        // The face and its hat layer, read as a 64x64 layout whatever the texture's resolution
        GuiCompat.blit(graphics, texture, x, y, FACE, FACE, 8.0f, 8.0f, 8, 8, 64, 64);
        GuiCompat.blit(graphics, texture, x, y, FACE, FACE, 40.0f, 8.0f, 8, 8, 64, 64);
    }

    @Nullable
    //? if <1.21.11 {
    private static ResourceLocation playerSkin(PlayerInfo info) {
    //?} else {
    private static Identifier playerSkin(PlayerInfo info) {
    //?}
        //? if <1.21 {
        return info.getSkinLocation();
        //?} else if <1.21.9 {
        return info.getSkin().texture();
        //?} else {
        return info.getSkin().body().texturePath();
        //?}
    }

    //? if <26.1 {
    private void drawScrollbar(GuiGraphics graphics, int x, int count, int scroll) {
    //?} else {
    private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int count, int scroll) {
    //?}
        int rows = visibleRows();
        if (count <= rows) return;
        int track = listBottom - listTop;
        int thumb = Math.max(8, track * rows / count);
        int top = listTop + (track - thumb) * scroll / Math.max(1, count - rows);
        graphics.fill(x, top, x + 2, top + thumb, 0xFF8A8B90);
    }

    //? if <26.1 {
    private void renderToasts(GuiGraphics graphics) {
    //?} else {
    private void renderToasts(GuiGraphicsExtractor graphics) {
    //?}
        // In the top-right corner, the oldest first, so they never cover the panel's buttons
        toasts.removeIf(ErrorToast::isExpired);
        int x = this.width - MARGIN - ErrorToast.WIDTH;
        int top = MARGIN;
        for (ErrorToast toast : toasts) {
            toast.render(graphics, x, top);
            top += toast.height() + TOAST_GAP;
        }
    }

    private String fit(String text, int room) {
        if (this.font.width(text) <= room) return text;
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end) + ellipsis) > room) end--;
        return text.substring(0, end) + ellipsis;
    }

    private int clampScroll(int scroll, int count) {
        return Math.max(0, Math.min(scroll, count - visibleRows()));
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // ----- Input -----

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseY >= listTop && mouseY < listBottom) {
            int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (mouseX >= playersX && mouseX < playersX + playersWidth) {
                List<PlayerRow> players = onlinePlayers();
                int index = row + playersScroll;
                if (index < players.size()) {
                    UUID id = players.get(index).id();
                    if (!picked.remove(id)) picked.add(id);
                    return true;
                }
            } else if (mouseX >= skinsX && mouseX < skinsX + skinsWidth) {
                int index = row + skinsScroll;
                if (index < skinRowCount()) {
                    // The scrollbar's column, right of the row's button, does nothing
                    if (mouseX < actionX() + 12) {
                        clickSkin(index, isOverAction(mouseX, mouseY, listTop + row * ROW_HEIGHT));
                    }
                    return true;
                }
            }
        }
        // A click outside the panel closes it, like the other dialogs
        if (!isOver(mouseX, mouseY, panelX, panelY, panelWidth, panelHeight)) {
            this.onClose();
            return true;
        }
        //? if <1.21.9 {
        return super.mouseClicked(mouseX, mouseY, button);
        //?} else {
        return super.mouseClicked(event, focused);
        //?}
    }

    private void clickSkin(int index, boolean action) {
        if (showPool) {
            ClientNetworkActions.SkinAdminView view = view();
            if (view == null || index >= view.pool().size()) return;
            String name = view.pool().get(index).name();
            if (action) {
                request(ClientNetworkActions.SkinAdminRequest.REMOVE_FROM_POOL, name);
            } else {
                request(ClientNetworkActions.SkinAdminRequest.GIVE_POOL_SKIN, name);
            }
        } else if (index < catalog.size()) {
            if (action) {
                addToPool(catalog.get(index));
            } else {
                giveCatalogSkin(catalog.get(index));
            }
        }
    }

    @Override
    //? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
    //?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    //?}
        int step = deltaY > 0 ? -1 : deltaY < 0 ? 1 : 0;
        if (mouseY >= listTop && mouseY < listBottom) {
            if (mouseX >= playersX && mouseX < playersX + playersWidth) {
                playersScroll = clampScroll(playersScroll + step, onlinePlayers().size());
                return true;
            }
            if (mouseX >= skinsX && mouseX < skinsX + skinsWidth) {
                skinsScroll = clampScroll(skinsScroll + step, skinRowCount());
                return true;
            }
        }
        //? if <1.21 {
        return super.mouseScrolled(mouseX, mouseY, deltaY);
        //?} else {
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        //?}
    }

    /** In singleplayer the game keeps running: the integrated server does the work this panel asks for. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (this.minecraft != null) {
            MenuGuiScale.leave(this.minecraft, this);
        }
        super.removed();
        BlurHandler.cleanup();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            //? if <26.2 {
            this.minecraft.setScreen(this.parent);
            //?} else {
            this.minecraft.gui.setScreen(this.parent);
            //?}
        }
    }
    //? if >=1.21 && <26.1 {
        //? if <1.21.2 {
    @Override
    public void renderBlurredBackground(float partialTick) {
        //?} else if <1.21.6 {
    @Override
    protected void renderBlurredBackground() {
        //?} else if <26.1 {
    @Override
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        //?}
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21.1 invokes this from super.render() after our modal has been drawn.
    }
        //?}
    //?}
    //? if >=26.1 {

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default background (panorama) - we render the parent screen's background manually
    }

    @Override
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
    //?}
}
