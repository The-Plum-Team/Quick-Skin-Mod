package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Helper class for opening native file dialogs
 * Uses TinyFileDialogs for cross-platform file selection
 */
@Environment(EnvType.CLIENT)
public class FileDialogHelper {
    private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean();

    /**
     * Opens a file dialog to select a PNG image or CPM model
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openSkinFileDialog(String title, Consumer<Path> onFileSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        ClientIoExecutor.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(2);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.cpmmodel"));
                filters.flip();

                String file = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "Skin Files (PNG, CPM Model)",
                    false
                );

                if (file != null && !file.isEmpty()) {
                    dispatch(onFileSelected, Path.of(file));
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.warn("Unable to open the skin file dialog", e);
            } finally {
                DIALOG_OPEN.set(false);
            }
        }).whenComplete((ignored, error) -> resetAfterSubmissionFailure(error));
    }

    /**
     * Opens a file dialog to select PNG or GIF images (capes)
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openCapeFileDialog(String title, Consumer<Path> onFileSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        ClientIoExecutor.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(2);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.gif"));
                filters.flip();

                String file = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "PNG/GIF Images",
                    false
                );

                if (file != null && !file.isEmpty()) {
                    dispatch(onFileSelected, Path.of(file));
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.warn("Unable to open the cape file dialog", e);
            } finally {
                DIALOG_OPEN.set(false);
            }
        }).whenComplete((ignored, error) -> resetAfterSubmissionFailure(error));
    }

    /**
     * Opens a file dialog to select multiple PNG images
     * @param title Dialog title
     * @param onFilesSelected Callback when files are selected
     */
    @SuppressWarnings("unused")
    public static void openMultipleFileDialog(String title, Consumer<Path[]> onFilesSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        ClientIoExecutor.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();

                String files = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "PNG Images",
                    true // Allow multiple selection
                );

                if (files != null && !files.isEmpty()) {
                    // TinyFileDialogs returns multiple files separated by |
                    String[] filePaths = files.split("\\|", 257);
                    if (filePaths.length > 256) {
                        QuickSkinInfo.LOGGER.warn("Ignoring a file dialog result with more than 256 files");
                        return;
                    }
                    Path[] paths = new Path[filePaths.length];
                    for (int i = 0; i < filePaths.length; i++) {
                        paths[i] = Path.of(filePaths[i]);
                    }
                    dispatch(onFilesSelected, paths);
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.warn("Unable to open the multi-file dialog", e);
            } finally {
                DIALOG_OPEN.set(false);
            }
        }).whenComplete((ignored, error) -> resetAfterSubmissionFailure(error));
    }

    private static <T> void dispatch(Consumer<T> consumer, T value) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> consumer.accept(value));
        }
    }

    private static void resetAfterSubmissionFailure(Throwable error) {
        if (error != null) {
            DIALOG_OPEN.set(false);
            QuickSkinInfo.LOGGER.warn("Unable to schedule a file dialog", error);
        }
    }
}
