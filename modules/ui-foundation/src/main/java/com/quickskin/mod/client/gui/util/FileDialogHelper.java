package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.3 {
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
//?} else {
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
//?}

import java.nio.file.Path;
//? if >=26.3 {
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
//?}
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

//? if <26.3 {
import static org.lwjgl.system.MemoryStack.stackPush;
//?}

/**
 * Helper class for opening native file dialogs.
 * Uses TinyFileDialogs before Minecraft 26.3 and SDL's asynchronous dialogs afterwards.
 */
@Environment(EnvType.CLIENT)
public class FileDialogHelper {
    private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean();
    private static final int MAX_SELECTED_FILES = 256;
    //? if >=26.3 {
    private static final AtomicReference<SdlDialogRequest> PENDING_SDL_DIALOG = new AtomicReference<>();
    //?}

    /**
     * Opens a file dialog to select a PNG image or CPM model
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openSkinFileDialog(String title, Consumer<Path> onFileSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        //? if >=26.3 {
        openSdlDialog(title, "Skin Files (PNG, CPM Model)", "png;cpmmodel", false,
                paths -> onFileSelected.accept(paths.get(0)));
        //?} else {
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
        //?}
    }

    /**
     * Opens a file dialog to select PNG or GIF images (capes)
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openCapeFileDialog(String title, Consumer<Path> onFileSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        //? if >=26.3 {
        openSdlDialog(title, "PNG/GIF Images", "png;gif", false,
                paths -> onFileSelected.accept(paths.get(0)));
        //?} else {
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
        //?}
    }

    /**
     * Opens a file dialog to select multiple PNG images
     * @param title Dialog title
     * @param onFilesSelected Callback when files are selected
     */
    @SuppressWarnings("unused")
    public static void openMultipleFileDialog(String title, Consumer<Path[]> onFilesSelected) {
        if (!DIALOG_OPEN.compareAndSet(false, true)) return;
        //? if >=26.3 {
        openSdlDialog(title, "PNG Images", "png", true,
                paths -> onFilesSelected.accept(paths.toArray(Path[]::new)));
        //?} else {
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
                    String[] filePaths = files.split("\\|", MAX_SELECTED_FILES + 1);
                    if (filePaths.length > MAX_SELECTED_FILES) {
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
        //?}
    }

    //? if >=26.3 {
    /**
     * SDL dialogs are asynchronous and must start on the main thread. The native filter strings
     * stay allocated until the result arrives; the callback itself may run on another thread.
     */
    private static void openSdlDialog(
            String title,
            String filterName,
            String pattern,
            boolean allowMany,
            Consumer<List<Path>> onSelected
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            DIALOG_OPEN.set(false);
            return;
        }
        minecraft.execute(() -> {
            SdlDialogRequest request = null;
            try {
                request = SdlDialogRequest.allocate(filterName, pattern, onSelected);
                int properties = request.properties();
                SDLProperties.SDL_SetStringProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
                SDLProperties.SDL_SetPointerProperty(
                        properties, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, request.filters().address());
                SDLProperties.SDL_SetNumberProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1);
                SDLProperties.SDL_SetPointerProperty(
                        properties, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, minecraft.getWindow().handle());
                SDLProperties.SDL_SetBooleanProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_MANY_BOOLEAN, allowMany);
                if (!PENDING_SDL_DIALOG.compareAndSet(null, request)) {
                    throw new IllegalStateException("another SDL file dialog is still pending");
                }
                SDLDialog.SDL_ShowFileDialogWithProperties(
                        SDLDialog.SDL_FILEDIALOG_OPENFILE, SdlDialogCallback.INSTANCE, 0L, properties);
            } catch (Throwable e) {
                QuickSkinInfo.LOGGER.warn("Unable to open the file dialog", e);
                if (request != null) {
                    PENDING_SDL_DIALOG.compareAndSet(request, null);
                    request.free();
                }
                DIALOG_OPEN.set(false);
            }
        });
    }

    private static void onSdlDialogResult(long userdata, long fileList, int filter) {
        SdlDialogRequest request = PENDING_SDL_DIALOG.getAndSet(null);
        if (request == null) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        if (fileList == 0L) {
            QuickSkinInfo.LOGGER.warn("Unable to open the file dialog: {}", SDLError.SDL_GetError());
        } else {
            for (int index = 0; ; index++) {
                long entry = MemoryUtil.memGetAddress(fileList + (long) index * Pointer.POINTER_SIZE);
                if (entry == 0L) {
                    break;
                }
                if (index == MAX_SELECTED_FILES) {
                    QuickSkinInfo.LOGGER.warn("Ignoring a file dialog result with more than {} files", MAX_SELECTED_FILES);
                    paths.clear();
                    break;
                }
                try {
                    paths.add(Path.of(MemoryUtil.memUTF8(entry)));
                } catch (InvalidPathException e) {
                    QuickSkinInfo.LOGGER.warn("Ignoring an invalid file dialog path", e);
                }
            }
        }
        Runnable finish = () -> {
            request.free();
            DIALOG_OPEN.set(false);
            if (!paths.isEmpty()) {
                request.onSelected().accept(List.copyOf(paths));
            }
        };
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(finish);
        } else {
            finish.run();
        }
    }

    private record SdlDialogRequest(
            Consumer<List<Path>> onSelected,
            ByteBuffer name,
            ByteBuffer pattern,
            SDL_DialogFileFilter.Buffer filters,
            int properties
    ) {
        static SdlDialogRequest allocate(String filterName, String pattern, Consumer<List<Path>> onSelected) {
            ByteBuffer nameBytes = MemoryUtil.memUTF8(filterName);
            ByteBuffer patternBytes = MemoryUtil.memUTF8(pattern);
            SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
            filters.get(0).name(nameBytes).pattern(patternBytes);
            int properties = SDLProperties.SDL_CreateProperties();
            SdlDialogRequest request = new SdlDialogRequest(onSelected, nameBytes, patternBytes, filters, properties);
            if (properties == 0) {
                request.free();
                throw new IllegalStateException("SDL_CreateProperties failed: " + SDLError.SDL_GetError());
            }
            return request;
        }

        void free() {
            if (properties != 0) {
                SDLProperties.SDL_DestroyProperties(properties);
            }
            filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
        }
    }

    /** One process-lifetime upcall stub; per-dialog state lives in {@link #PENDING_SDL_DIALOG}. */
    private static final class SdlDialogCallback {
        private static final SDL_DialogFileCallback INSTANCE =
                SDL_DialogFileCallback.create(FileDialogHelper::onSdlDialogResult);
    }
    //?}

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
