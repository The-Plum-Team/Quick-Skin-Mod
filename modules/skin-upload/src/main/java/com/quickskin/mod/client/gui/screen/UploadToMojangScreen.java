package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.util.MojangSkinUploader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class UploadToMojangScreen extends Screen {
    private final Screen parent;
    private final AssetMetadata metadata;
    private final Consumer<Boolean> callback;

    // Panel styling
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background for frosted glass effect
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline
    // Keep the Stonecutter seam aligned with the release branches; both APIs require opaque ARGB.
    //? if <1.21.11 {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    private static final int INFO_COLOR = 0xFF40A040;         // Opaque green info text
    private static final int ERROR_COLOR = 0xFFFF4040;        // Opaque red error text
    private static final int SUCCESS_COLOR = 0xFF40FF40;      // Opaque bright green success text
    //?} else {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    private static final int INFO_COLOR = 0xFF40A040;         // Opaque green info text
    private static final int ERROR_COLOR = 0xFFFF4040;        // Opaque red error text
    private static final int SUCCESS_COLOR = 0xFF40FF40;      // Opaque bright green success text
    //?}

    // Panel dimensions
    private final int panelWidth = 380;
    private final int panelHeight = 220;
    private int panelX;
    private int panelY;

    // Upload state
    private boolean isUploading = false;
    private boolean uploadComplete = false;
    private String resultMessage = null;
    private boolean uploadSuccess = false;
    private boolean active;
    private long uploadAttempt;
    private CompletableFuture<MojangSkinUploader.UploadResult> uploadTask;

    private Button cancelButton;
    private Button uploadButton;

    public UploadToMojangScreen(Screen parent, AssetMetadata metadata, Consumer<Boolean> callback) {
        super(Component.translatable("quickskin.screen.upload.title"));
        this.parent = parent;
        this.metadata = metadata;
        this.callback = callback;
    }

    @Override
    protected void init() {
        active = true;

        // Calculate centered panel position
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Button dimensions
        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonSpacing = 10;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;

        // Calculate button positions (centered, side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Cancel/Close button (left)
        this.cancelButton = ButtonFactory.createStyled(
            buttonStartX, buttonY, buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.cancel"),
            (button) -> this.callback.accept(false)
        );

        // Upload button (right, green/success)
        this.uploadButton = ButtonFactory.createStyled(
            buttonStartX + buttonWidth + buttonSpacing, buttonY,
            buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.upload_skin"),
            (button) -> this.uploadSkin()
        );

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(uploadButton);
    }

    private void uploadSkin() {
        if (isUploading) return;

        // Reset state for retry
        uploadComplete = false;
        resultMessage = null;
        uploadSuccess = false;

        // Disable buttons during upload
        isUploading = true;
        uploadButton.active = false;
        uploadButton.setMessage(Component.translatable("quickskin.button.upload_skin"));
        cancelButton.active = false;

        // Upload on the process-owned bounded client I/O executor.
        long attempt = ++uploadAttempt;
        var client = minecraft;
        CompletableFuture<MojangSkinUploader.UploadResult> task =
                ClientIoExecutor.supplyAsync(() -> MojangSkinUploader.uploadSkin(metadata));
        uploadTask = task;
        task.whenComplete((result, error) -> {
            if (client != null) {
                client.execute(() -> completeUpload(attempt, result, error));
            }
        });
    }

    private void completeUpload(
            long attempt,
            MojangSkinUploader.UploadResult result,
            Throwable error) {
        if (!active || attempt != uploadAttempt) return;
        uploadTask = null;

        if (error != null) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = Component.translatable("quickskin.error.unknown").getString();
            }
            result = new MojangSkinUploader.UploadResult(
                    false,
                    Component.translatable("quickskin.error.unexpected_error", detail).getString(),
                    0);
        }

        isUploading = false;
        uploadComplete = true;
        uploadSuccess = result.success;
        resultMessage = result.message;

        // Update button text
        cancelButton.active = true;
        cancelButton.setMessage(Component.translatable("quickskin.button.close"));

        if (!result.success) {
            uploadButton.active = true;
            uploadButton.setMessage(Component.translatable("quickskin.button.retry"));
        }
    }

    @Override
    //? if <26.1.2 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        // Render parent screen in background
        if (this.parent != null) {
            //? if <26.1.2 {
            this.parent.render(graphics, -1, -1, partialTicks);
            //?} else {
            GuiCompat.extractParent(this.parent, graphics, partialTicks);
            //?}
        }

        //? if <1.21.11 {
        graphics.flush();
        //?}
        BlurHandler.renderBlur();

        // Draw lighter overlay over entire screen (so blur is more visible)
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        // Draw main panel background with frosted glass effect
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth,
                     this.panelY + this.panelHeight,
                     PANEL_BG);

        // Draw subtle outline around panel
        // Top
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + 1,
                     PANEL_OUTLINE);
        // Bottom
        graphics.fill(this.panelX, this.panelY + this.panelHeight - 1,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Left
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + 1, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Right
        graphics.fill(this.panelX + this.panelWidth - 1, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);

        // Draw title (centered)
        int titleY = this.panelY + 20;
        //? if <26.1.2 {
        graphics.drawCenteredString(this.font, this.title,
        //?} else {
        graphics.centeredText(this.font, this.title,
        //?}
                                   this.width / 2, titleY,
                                   TITLE_COLOR);

        // Draw status icon and messages
        int iconY = this.panelY + 40;
        int messageY = this.panelY + 60;
        int lineHeight = 12;
        int currentY = messageY;

        if (isUploading) {
            // Show uploading state
            String uploadIcon = "\u2191";  // ↑
            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, uploadIcon,
            //?} else {
            graphics.centeredText(this.font, uploadIcon,
            //?}
                                       this.width / 2, iconY,
                                       INFO_COLOR);

            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, Component.translatable("quickskin.upload.uploading").getString(),
            //?} else {
            graphics.centeredText(this.font, Component.translatable("quickskin.upload.uploading").getString(),
            //?}
                                       this.width / 2, currentY,
                                       INFO_COLOR);
            currentY += lineHeight * 2;

            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, Component.translatable("quickskin.upload.please_wait").getString(),
            //?} else {
            graphics.centeredText(this.font, Component.translatable("quickskin.upload.please_wait").getString(),
            //?}
                                       this.width / 2, currentY,
                                       MESSAGE_COLOR);
        } else if (uploadComplete) {
            // Show result
            String icon = uploadSuccess ? "\u2713" : "\u2717";  // ✓ or ✗
            int iconColor = uploadSuccess ? SUCCESS_COLOR : ERROR_COLOR;

            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, icon,
            //?} else {
            graphics.centeredText(this.font, icon,
            //?}
                                       this.width / 2, iconY,
                                       iconColor);

            // Word wrap the result message
            if (resultMessage != null) {
                java.util.List<String> wrappedLines = wrapText(resultMessage, this.panelWidth - 40);
                for (String line : wrappedLines) {
                    //? if <26.1.2 {
                    graphics.drawCenteredString(this.font, line,
                    //?} else {
                    graphics.centeredText(this.font, line,
                    //?}
                                               this.width / 2, currentY,
                                               iconColor);
                    currentY += lineHeight;
                }
            }

            if (uploadSuccess) {
                currentY += lineHeight;
                //? if <26.1.2 {
                graphics.drawCenteredString(this.font, Component.translatable("quickskin.upload.success").getString(),
                //?} else {
                graphics.centeredText(this.font, Component.translatable("quickskin.upload.success").getString(),
                //?}
                                           this.width / 2, currentY,
                                           MESSAGE_COLOR);
            }
        } else {
            // Show initial instructions
            String uploadIcon = "\u2191";  // ↑
            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, uploadIcon,
            //?} else {
            graphics.centeredText(this.font, uploadIcon,
            //?}
                                       this.width / 2, iconY,
                                       INFO_COLOR);

            String[] instructions = {
                Component.translatable("quickskin.upload.prompt").getString(),
                "",
                Component.translatable("quickskin.upload.description_1").getString(),
                Component.translatable("quickskin.upload.description_2").getString(),
                "",
                Component.translatable("quickskin.upload.skin_label", truncatePath(metadata.friendlyName(), 35)).getString(),
                Component.translatable("quickskin.upload.model_label", Component.translatable(
                    "slim".equals(metadata.skinModel() != null ? metadata.skinModel().toLowerCase(Locale.ROOT) : null)
                        ? "quickskin.upload.model_slim"
                        : "quickskin.upload.model_classic"
                ).getString()).getString()
            };

            for (String line : instructions) {
                //? if <26.1.2 {
                graphics.drawCenteredString(this.font, line,
                //?} else {
                graphics.centeredText(this.font, line,
                //?}
                                           this.width / 2, currentY,
                                           MESSAGE_COLOR);
                currentY += lineHeight;
            }
        }

        // Render buttons
        //? if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        //?}
    }

    private String truncatePath(String path, int maxLength) {
        if (path.length() <= maxLength) {
            return path;
        }
        // Show beginning and end of path
        int halfLength = (maxLength - 3) / 2;
        return path.substring(0, halfLength) + "..." + path.substring(path.length() - halfLength);
    }

    private java.util.List<String> wrapText(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.isEmpty() ? word : currentLine + " " + word;
            int lineWidth = this.font.width(testLine);

            if (lineWidth > maxWidth && !currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                if (!currentLine.isEmpty()) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    @Override
    public void removed() {
        active = false;
        uploadAttempt++;
        if (uploadTask != null) {
            uploadTask.cancel(false);
            uploadTask = null;
        }
        super.removed();
        // Cleanup blur resources
        BlurHandler.cleanup();
    }

    @Override
    //? if <1.21.11 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
    //?}
        // Check if click is outside the panel
        if (mouseX < this.panelX || mouseX > this.panelX + this.panelWidth ||
            mouseY < this.panelY || mouseY > this.panelY + this.panelHeight) {
            // Click outside panel - close the modal without confirming
            this.onClose();
            return true;
        }
        // Click inside panel - handle normally
        //? if <1.21.11 {
        return super.mouseClicked(mouseX, mouseY, button);
        //?} else {
        return super.mouseClicked(event, focused);
        //?}
    }

    @Override
    public void onClose() {
        // Return to parent screen without confirming
        this.callback.accept(false);
    }
    //? if >=1.21 {
        //? if <1.21.11 {
    @Override
    public void renderBlurredBackground(float partialTick) {
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
        //?}
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21.1 invokes this from super.render() after our modal has been drawn.
    }
        //?}
    //?}
    //? if >=26.1.2 {

    @Override
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
    //?}
}
