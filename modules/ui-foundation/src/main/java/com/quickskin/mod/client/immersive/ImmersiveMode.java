package com.quickskin.mod.client.immersive;

import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The immersive view of Quick Skin's screens inside a world: the camera leaves the player's eyes,
 * swings round to look at the player, and the screen floats round them in the world, with the
 * mouse pointing at it. Closing the screen brings the camera back.
 *
 * <p>This class decides, once per frame, how far the view has come and where the screen stands
 * ({@link #beginFrame}), and puts the camera there once the game has placed its own for that
 * frame ({@link #place}); the game's hooks ask it for the field of view, the mouse and the
 * screen's picture. Nothing of the player or of the game's options changes except the hidden
 * HUD, which is put back when the view ends. The player the camera looks at is the real one, so a
 * skin worn from the screen shows on them at once.
 *
 * <p>The camera turns half way round the player in well under a second, fast enough for uneven
 * steps to show as stutter at any frame rate, so the view keeps its own time with care
 * ({@link #step}): it goes by the time the game took at the start of each frame, before its
 * ticks, never by a clock read later in the frame, which the length of a tick would move; it
 * steps by the middle one of the latest frames' lengths, so one slow frame is waited out instead
 * of jumped over; and it does not leave the player's eyes until the screen, whose first build and
 * draw are slow, has been drawn.
 */
public final class ImmersiveMode {
    /** How long the camera takes to reach its place, and to come back. */
    private static final float OPEN_SECONDS = 0.65f;
    private static final float CLOSE_SECONDS = 0.4f;
    /** The field of view at rest: narrower than the game's, so the player is not distorted. */
    private static final float REST_FOV = 50.0f;
    /** With a wall close in front of the player the view widens instead, up to this. */
    private static final float MAX_FOV = 100.0f;
    /** The camera never rests nearer the player than this, in blocks. */
    private static final float MIN_DEPTH = 0.9f;
    /** The camera stays this far from whatever is in its way. */
    private static final float WALL_CLEARANCE = 0.35f;
    /** The player is drawn once the camera is this far from their eyes. */
    private static final float SHOW_PLAYER_DISTANCE = 1.1f;
    /** Where an unpointed mouse is put, in window coordinates: over nothing. */
    private static final double NOWHERE = -8192.0;
    /** The view steps by the middle one of this many frames' lengths. */
    private static final int STEP_FRAMES = 5;
    /** No frame counts for longer than this, in seconds. */
    private static final float LONGEST_FRAME = 0.1f;
    /** Frames the camera waits at the player's eyes for, while the screen is built and first drawn. */
    private static final int WARM_UP_FRAMES = 2;

    private static boolean running;
    private static boolean revealThroughBlocks;

    /** The client renderer that reveals the player through terrain owns the unconstrained orbit. */
    public static void enableBlockReveal() {
        revealThroughBlocks = true;
    }

    /** Counts the views opened, so what one view was told is not taken for the next one's. */
    private static int session;
    /** Whether the world is being drawn now, as opposed to the GUI over it. */
    private static boolean worldPass;
    private static boolean opening;
    /** Whether the camera has reached its place since the view opened. */
    private static boolean settled;
    private static float progress;
    /** The game's time at the start of the last frame. */
    private static long lastFrameNanos;
    /** The time the game took as this frame started, before its ticks, as {@link #frameStarted} was told; 0 for none. */
    private static long frameStartNanos;
    /** The latest frames' lengths in seconds, as a ring. */
    private static final float[] frameSeconds = new float[STEP_FRAMES];
    private static int framesKept;
    private static int frameAt;
    private static int warmUp;
    private static boolean hudWasHidden;
    private static float startYaw;
    private static float orbit;
    private static float tilt;
    private static float tiltTarget;
    private static float restDepth;
    /** How far the player is turned away from the camera, and how far the screen asks for. */
    private static float playerTurn;
    private static float playerTurnTarget;
    /** The turn the screen last asked for, so a change of screen is told from a frame of the same screen. */
    private static float askedTurn;
    /**
     * Where the player stands on the screen and how large, as shares of the screen's height and
     * width; they glide from one screen's place to the next one's, so the camera moves there
     * smoothly instead of jumping when a screen opened over another puts the player elsewhere.
     */
    private static boolean glided;
    private static float glideFeetX;
    private static float glideFeetY;
    private static float glideScale;
    private static double vanillaFov = 70.0;
    @Nullable
    private static ImmersiveLayout layout;
    private static float canvasWidth;
    private static float canvasHeight;

    @Nullable
    private static ImmersiveFrame frame;
    @Nullable
    private static Screen frameScreen;
    private static float fov;

    /** Whether {@link #beginFrame} worked out where the screen stands this frame, for {@link #place}. */
    private static boolean rigged;
    private static double feetX;
    private static double feetY;
    private static double feetZ;
    private static float headHeight;
    private static float restYaw;
    private static float depth;
    private static float unitsPerPixel;
    private static final Vector3f restForward = new Vector3f();
    private static final Vector3f restRight = new Vector3f();
    private static final Vector3f restUp = new Vector3f();
    private static final Vector3f sheetCentre = new Vector3f();
    private static final Vector3f rest = new Vector3f();

    private static double realCursorX;
    private static double realCursorY;
    /** The last surface under the free mouse, carried forward while a button is held. */
    private static int cursorPart = ImmersiveFrame.NONE;
    private static boolean cursorToRestore;
    /** How far the mouse is from the point of the screen it points at, in GUI units. */
    private static float aboveX;
    private static float aboveY;
    /** What the open screen draws above itself this frame, once the rest of it is drawn. */
    private static final java.util.List<Runnable> drawnAbove = new java.util.ArrayList<>();

    private ImmersiveMode() {
    }

    /** Where the game's camera goes this frame, and whether the player is drawn. */
    public record Camera(double x, double y, double z, float yaw, float pitch, boolean showsPlayer) {
    }

    /** Whether the player asked for the immersive view. */
    public static boolean enabled() {
        return ClientConfig.getInstance().immersiveMenu;
    }

    /** Whether a screen floats round the player now, so it draws no background or model of its own. */
    public static boolean active() {
        return running && opening;
    }

    /** Whether the camera is away from the player's eyes, on its way out, at rest or coming back. */
    public static boolean running() {
        return running;
    }

    /** Which view this is, counting from the first one opened. */
    public static int session() {
        return session;
    }

    /**
     * The way the player faces: towards the camera's resting place, the way they looked when the
     * view opened, turned as far as the screen asks ({@link ImmersiveScreen#immersivePlayerTurn}).
     */
    public static float faceYaw() {
        return startYaw + playerTurn;
    }

    /**
     * The way the player will face once turned as the screen asks, which is what other players are
     * told: their clients ease the turn themselves, so they need no step of it.
     */
    public static float faceYawTarget() {
        return startYaw + playerTurnTarget;
    }

    /** How far the player is turned from the camera now, on the way to the screen's turn. */
    public static float playerTurn() {
        return playerTurn;
    }

    /** How far the camera is from the player's eyes (0) on its way to its resting place (1). */
    public static float settle() {
        return running ? ease(progress) : 0.0f;
    }

    /** Told when the game starts and ends drawing the world of a frame. */
    public static void worldPass(boolean drawing) {
        worldPass = drawing;
    }

    /** Whether the world is being drawn now during a view, which is when the player plays the menu's pose. */
    public static boolean posesPlayer() {
        return running && worldPass;
    }

    /** Whether {@code screen} is drawn into the floating picture this frame. */
    public static boolean captures(@Nullable Screen screen) {
        return active() && frame != null && screen != null && screen == openScreen(Minecraft.getInstance());
    }

    /** The field of view the world is drawn with, given the game's own. */
    public static double fov(double vanilla) {
        vanillaFov = vanilla;
        return running && rigged ? fov : vanilla;
    }

    /** How far the camera has been turned round the player, in degrees. */
    public static float orbit() {
        return orbit;
    }

    public static void setOrbit(float degrees) {
        orbit = degrees;
    }

    /**
     * Told the time the game takes as a frame starts, before its ticks, where the game hands it to
     * nothing the view hooks (from 1.21: the start of {@code Minecraft.runTick}). 1.20.1 hands it
     * to {@code GameRenderer.render}, which passes it to {@link #beginFrame(float, long)} itself.
     */
    public static void frameStarted(long nanos) {
        frameStartNanos = nanos;
    }

    /**
     * {@link #beginFrame(float, long)} with the time {@link #frameStarted} was last told, or the
     * time now while it was told none.
     */
    public static void beginFrame(float partialTick) {
        long started = frameStartNanos;
        beginFrame(partialTick, started != 0L ? started : System.nanoTime());
    }

    /**
     * Decides how far the view has come this frame and where the screen stands. Called before
     * anything of the frame is drawn or the mouse is read, with the time the game took at the
     * start of the frame.
     */
    public static void beginFrame(float partialTick, long frameNanos) {
        Minecraft minecraft = Minecraft.getInstance();
        ImmersiveTrace.frameDrawn();
        float measured = lastFrameNanos == 0L ? 0.0f : (frameNanos - lastFrameNanos) / 1.0e9f;
        lastFrameNanos = frameNanos;
        float seconds = step(measured);

        LocalPlayer player = minecraft.player;
        Screen screen = openScreen(minecraft);
        boolean inWorld = player != null && minecraft.level != null;
        boolean wanted = ImmersiveSurface.SUPPORTED && inWorld && enabled()
                && minecraft.getCameraEntity() == player && player.isAlive() && !player.isSleeping()
                && !player.isSpectator() && shows(screen);
        if (!running) {
            if (!wanted) return;
            start(minecraft, player, partialTick);
        }
        if (!inWorld) {
            stop(minecraft);
            return;
        }
        if (opening && !wanted) {
            // The mouse is the game's again from here on
            cursorToRestore = true;
            ImmersiveTrace.begin("close");
        }
        opening = wanted;
        if (warmUp > 0) {
            // The screen is being built and drawn for the first time: the camera stays at the eyes
            warmUp--;
            seconds = 0.0f;
        }
        progress = Mth.clamp(progress + (opening ? seconds / OPEN_SECONDS : -seconds / CLOSE_SECONDS), 0.0f, 1.0f);
        ImmersiveTrace.frame(measured, seconds, progress);
        if (progress >= 1.0f) {
            if (!settled) {
                ImmersiveTrace.end();
            }
            settled = true;
        }
        if (!opening && progress <= 0.0f) {
            ImmersiveTrace.end();
            stop(minecraft);
            return;
        }
        if (opening) {
            ImmersiveLayout declared = screen instanceof ImmersiveScreen immersive ? immersive.immersiveLayout() : null;
            if (declared != null) {
                layout = declared;
                tiltTarget = 1.0f;
                // The player turns as the screen asks, and keeps that turn through its dialogs. The
                // turn is from where the camera stands when the screen asks for it, so a screen that
                // shows the player's back shows it after an orbit too; a later orbit then goes round them
                float asked = ((ImmersiveScreen) screen).immersivePlayerTurn();
                if (asked != askedTurn) {
                    askedTurn = asked;
                    playerTurnTarget = asked == 0.0f ? 0.0f : asked + orbit;
                }
            } else {
                // A dialog over the screen: the panels fold flat behind it
                tiltTarget = 0.0f;
            }
            canvasWidth = minecraft.getWindow().getGuiScaledWidth();
            canvasHeight = minecraft.getWindow().getGuiScaledHeight();
        }
        tilt += (tiltTarget - tilt) * (1.0f - (float) Math.exp(-seconds * 10.0f));
        playerTurn += (playerTurnTarget - playerTurn) * (1.0f - (float) Math.exp(-seconds * 6.0f));
        if (layout == null || canvasWidth <= 0.0f || canvasHeight <= 0.0f) {
            frame = null;
            rigged = false;
            return;
        }
        rig(minecraft, player, partialTick, seconds);
    }

    /**
     * How far the view moves this frame, in seconds, given the time since the last frame began.
     * The middle one of the latest frames' lengths is taken, not this frame's own: a frame that
     * took far longer than its neighbours (the screen being built, a chunk arriving) is then
     * waited out, where its own length would throw the camera forward, and the small unevenness
     * between ordinary frames is not passed on to the camera either. A lasting change of frame
     * rate takes over after three frames.
     */
    private static float step(float measured) {
        if (measured <= 0.0f || measured > 1.0f) {
            // The first frame, or the game stood still: nothing to go by yet
            framesKept = 0;
            return 0.0f;
        }
        frameSeconds[frameAt] = Math.min(measured, LONGEST_FRAME);
        frameAt = (frameAt + 1) % STEP_FRAMES;
        framesKept = Math.min(STEP_FRAMES, framesKept + 1);
        float[] sorted = java.util.Arrays.copyOf(frameSeconds, framesKept);
        java.util.Arrays.sort(sorted);
        return sorted[(framesKept - 1) / 2];
    }

    /** Whether the immersive view shows this screen: one that asks for it, or a screen opened over it. */
    private static boolean shows(@Nullable Screen screen) {
        if (screen == null) return false;
        if (screen instanceof ImmersiveScreen immersive) {
            return running || immersive.immersiveLayout() != null;
        }
        return running && opening && screen.getClass().getName().startsWith("com.quickskin.mod.");
    }

    private static void start(Minecraft minecraft, LocalPlayer player, float partialTick) {
        running = true;
        session++;
        opening = true;
        settled = false;
        progress = 0.0f;
        orbit = 0.0f;
        tilt = 0.0f;
        tiltTarget = 1.0f;
        restDepth = -1.0f;
        playerTurn = 0.0f;
        playerTurnTarget = 0.0f;
        askedTurn = 0.0f;
        glided = false;
        layout = null;
        frame = null;
        rigged = false;
        warmUp = WARM_UP_FRAMES;
        cursorPart = ImmersiveFrame.NONE;
        cursorToRestore = false;
        ImmersiveTrace.begin("open");
        startYaw = player.getViewYRot(partialTick);
        realCursorX = minecraft.mouseHandler.xpos();
        realCursorY = minecraft.mouseHandler.ypos();
        hudWasHidden = hudHidden(minecraft);
        hideHud(minecraft, true);
    }

    private static void stop(Minecraft minecraft) {
        running = false;
        opening = false;
        progress = 0.0f;
        frame = null;
        frameScreen = null;
        rigged = false;
        layout = null;
        hideHud(minecraft, hudWasHidden);
    }

    /**
     * Works out where the screen stands round the player this frame and the field of view, which
     * the game asks for before it places its camera; {@link #place} then puts the camera there.
     */
    private static void rig(Minecraft minecraft, LocalPlayer player, float partialTick, float seconds) {
        ImmersiveLayout current = layout;
        feetX = Mth.lerp(partialTick, player.xo, player.getX());
        feetY = Mth.lerp(partialTick, player.yo, player.getY());
        feetZ = Mth.lerp(partialTick, player.zo, player.getZ());
        headHeight = player.getEyeHeight();

        // At rest the camera faces the way the player looked from, level with the ground
        restYaw = startYaw + 180.0f + orbit;
        restForward.set(forward(restYaw, 0.0f));
        restRight.set(restForward).cross(0.0f, 1.0f, 0.0f).normalize();
        restUp.set(restRight).cross(restForward);

        // Where the screen puts the player, glided to from where the screen before it did
        float shareX = current.feetX() / canvasWidth;
        float shareY = current.feetY() / canvasHeight;
        float shareScale = Math.max(1.0f, current.pixelsPerBlock()) / canvasHeight;
        if (!glided) {
            glided = true;
            glideFeetX = shareX;
            glideFeetY = shareY;
            glideScale = shareScale;
        } else {
            float glide = 1.0f - (float) Math.exp(-seconds * 8.0f);
            glideFeetX += (shareX - glideFeetX) * glide;
            glideFeetY += (shareY - glideFeetY) * glide;
            glideScale += (shareScale - glideScale) * glide;
        }
        float layoutFeetX = glideFeetX * canvasWidth;
        float layoutFeetY = glideFeetY * canvasHeight;

        // The sheet stands where its drawing of the player is as tall as the player
        float pixelsPerBlock = Math.max(1.0f, glideScale * canvasHeight);
        float restTan = (float) Math.tan(Math.toRadians(REST_FOV) / 2.0);
        float wantedDepth = canvasHeight / (2.0f * pixelsPerBlock * restTan);
        float room = revealThroughBlocks ? wantedDepth : room(minecraft, player, feetX, feetY, feetZ,
                restForward, restRight, restUp, layoutFeetX, layoutFeetY, 1.0f / pixelsPerBlock, wantedDepth);
        float targetDepth = Math.max(MIN_DEPTH, Math.min(wantedDepth, room));
        restDepth = restDepth < 0.0f ? targetDepth
                : restDepth + (targetDepth - restDepth) * (1.0f - (float) Math.exp(-seconds * 8.0f));
        depth = restDepth;
        // Nearer than it wants to be, the camera widens its view to keep the player's size
        float restFov = (float) Math.min(MAX_FOV,
                Math.toDegrees(2.0 * Math.atan(canvasHeight / (2.0 * pixelsPerBlock * depth))));
        unitsPerPixel = (float) (2.0 * depth * Math.tan(Math.toRadians(restFov) / 2.0) / canvasHeight);

        // Everything below is relative to the player's feet
        float sheetX = (layoutFeetX - canvasWidth / 2.0f) * unitsPerPixel;
        float sheetY = (canvasHeight / 2.0f - layoutFeetY) * unitsPerPixel;
        sheetCentre.zero().fma(-sheetX, restRight).fma(-sheetY, restUp);
        rest.set(sheetCentre).fma(-depth, restForward);

        fov = (float) (vanillaFov + (restFov - vanillaFov) * ease(progress));
        rigged = true;
    }

    /**
     * Puts the camera where the view has come to this frame, given where the game itself has just
     * put it: at the player's eyes, or behind or before them in third person. The view leaves
     * from there and returns there, so it is this frame's place that counts: the place of the
     * frame before lags a moving player by a frame. Answers null while the game's camera stands.
     */
    @Nullable
    public static Camera place(double gameX, double gameY, double gameZ, float gameYaw, float gamePitch,
                               boolean gameShowsPlayer) {
        ImmersiveLayout current = layout;
        if (!running || !rigged || current == null) {
            return null;
        }
        float eased = ease(progress);
        Vector3f eyes = new Vector3f((float) (gameX - feetX), (float) (gameY - feetY), (float) (gameZ - feetZ));
        if (eyes.lengthSquared() > 64.0f * 64.0f) {
            eyes.set(0.0f, headHeight, 0.0f);
        }

        // On its way the camera circles from the eyes out to its place, always facing the sheet.
        // It backs away faster than it turns, so the player's head never fills the view.
        float away = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
        Vector3f aim = new Vector3f(eyes).lerp(sheetCentre, eased);
        // On its way out the camera always turns the same way round the player, half a turn or so;
        // once it has been at rest it comes back the short way from wherever it was turned to
        float turn = settled ? Mth.wrapDegrees(restYaw - gameYaw) : -Mth.positiveModulo(gameYaw - restYaw, 360.0f);
        float yaw = gameYaw + turn * eased;
        float pitch = gamePitch * (1.0f - eased);
        Vector3f lookForward = forward(yaw, pitch);
        Vector3f position = new Vector3f(aim).fma(-depth * away, lookForward);
        Vector3f lookRight = new Vector3f(lookForward).cross(0.0f, 1.0f, 0.0f);
        if (lookRight.lengthSquared() < 1.0e-6f) {
            lookRight.set(restRight);
        }
        lookRight.normalize();
        Vector3f lookUp = new Vector3f(lookRight).cross(lookForward);

        Matrix4f rigToCamera = basis(lookRight, lookUp, lookForward).transpose()
                .translate(rest.x - position.x, rest.y - position.y, rest.z - position.z)
                .mul(basis(restRight, restUp, restForward));
        ImmersiveFrame next = new ImmersiveFrame(canvasWidth, canvasHeight, fov, depth, unitsPerPixel, rigToCamera,
                current.panels(), tilt);
        Screen screen = openScreen(Minecraft.getInstance());
        cursorPart = frame != null && frameScreen == screen ? next.partFrom(frame, cursorPart) : ImmersiveFrame.NONE;
        frame = next;
        frameScreen = screen;
        Vector3f head = new Vector3f(0.0f, headHeight, 0.0f);
        return new Camera(feetX + position.x, feetY + position.y, feetZ + position.z, yaw, pitch,
                gameShowsPlayer || position.distance(head) > SHOW_PLAYER_DISTANCE);
    }

    /** How far from the sheet the camera can stand before something of the world is in its way. */
    private static float room(Minecraft minecraft, LocalPlayer player, double feetX, double feetY, double feetZ,
                              Vector3f forward, Vector3f right, Vector3f up, float layoutFeetX,
                              float layoutFeetY, float unitsPerPixel, float depth) {
        float sheetX = (layoutFeetX - canvasWidth / 2.0f) * unitsPerPixel;
        float sheetY = (canvasHeight / 2.0f - layoutFeetY) * unitsPerPixel;
        Vector3f place = new Vector3f().fma(-sheetX, right).fma(-sheetY, up).fma(-depth, forward);
        Vec3 chest = new Vec3(feetX, feetY + player.getEyeHeight() * 0.8, feetZ);
        Vec3 wanted = new Vec3(feetX + place.x, feetY + place.y, feetZ + place.z);
        double reach = chest.distanceTo(wanted);
        if (reach < 1.0e-3 || minecraft.level == null) return depth;
        HitResult hit = minecraft.level.clip(new ClipContext(chest, wanted, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return depth;
        double free = Math.max(0.0, hit.getLocation().distanceTo(chest) - WALL_CLEARANCE);
        return (float) (depth * Math.min(1.0, free / reach));
    }

    /** Draws the floating screen over the world; called once the frame's screen has been drawn. */
    public static void drawOverlay() {
        if (!running || frame == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        // Another screen took its place: the floating screen is gone at once, under it
        if (!opening && openScreen(minecraft) != null) return;
        float eased = ease(progress);
        // The screen takes shape once the camera has turned towards it, and leaves with the camera
        float alpha = opening ? Mth.clamp((eased - 0.35f) / 0.5f, 0.0f, 1.0f) : eased * eased;
        ImmersiveSurface.draw(frame, alpha, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(), opening ? aboveX : 0.0f, opening ? aboveY : 0.0f);
    }

    /**
     * Draws something of {@code screen} that lies above it and follows the mouse, such as a
     * tooltip of its own making. In the immersive view it is drawn after the screen, with the
     * screen's own tooltip, and floats flat beside the mouse; otherwise it is drawn at once.
     */
    public static void above(Screen screen, Runnable drawing) {
        if (captures(screen) && ImmersiveSurface.capturing()) {
            drawnAbove.add(drawing);
        } else {
            drawing.run();
        }
    }

    /**
     * How far, in GUI units across, the mouse is from the point of the floating screen it points
     * at: what the screen draws above itself (its tooltip) is moved by this, from where it points
     * to where it is. 0 while it points at nothing.
     */
    public static float aboveX() {
        return aboveX;
    }

    /** How far down the mouse is from the point of the floating screen it points at, as {@link #aboveX}. */
    public static float aboveY() {
        return aboveY;
    }

    /** Draws what {@link #above} held back; called once the screen itself has been drawn. */
    public static void drawAbove() {
        if (drawnAbove.isEmpty()) return;
        java.util.List<Runnable> drawings = new java.util.ArrayList<>(drawnAbove);
        drawnAbove.clear();
        for (Runnable drawing : drawings) {
            drawing.run();
        }
    }

    /**
     * Remembers where the mouse really is, and says where on the floating screen it points.
     * {@code held} is whether a mouse button is down, as the game's mouse handler has it.
     */
    public static void cursorMoved(double[] position, boolean held) {
        realCursorX = position[0];
        realCursorY = position[1];
        point(position, held);
    }

    /**
     * Where the mouse really is across the window, in the screen's units. A drag that turns the
     * camera goes by this: where the mouse points moves with the camera it is turning, and stops
     * meaning anything once the mouse is past the window's edge.
     */
    public static double realMouseX() {
        Minecraft minecraft = Minecraft.getInstance();
        return realCursorX * minecraft.getWindow().getGuiScaledWidth()
                / Math.max(1, minecraft.getWindow().getScreenWidth());
    }

    /** Where the mouse really is down the window, in the screen's units, as {@link #realMouseX}. */
    public static double realMouseY() {
        Minecraft minecraft = Minecraft.getInstance();
        return realCursorY * minecraft.getWindow().getGuiScaledHeight()
                / Math.max(1, minecraft.getWindow().getScreenHeight());
    }

    /**
     * Where the mouse points this frame, in window coordinates, or null while the mouse is left
     * alone. The floating screen moves under a still mouse while the camera travels.
     */
    @Nullable
    public static double[] cursor(boolean held) {
        double[] position = {realCursorX, realCursorY};
        if (cursorToRestore) {
            cursorToRestore = false;
            return position;
        }
        return point(position, held) ? position : null;
    }

    /** Turns the mouse's place in the window into the place of the screen it points at. */
    private static boolean point(double[] position, boolean pressed) {
        Minecraft minecraft = Minecraft.getInstance();
        ImmersiveFrame current = frame;
        if (!active() || current == null) return false;
        double windowWidth = Math.max(1, minecraft.getWindow().getScreenWidth());
        double windowHeight = Math.max(1, minecraft.getWindow().getScreenHeight());
        float x = (float) (position[0] * current.width() / windowWidth);
        float y = (float) (position[1] * current.height() / windowHeight);

        // A held button keeps the mouse on the part it was pressed on, beyond its edges and beyond
        // the window's: a drag that leaves the window still points somewhere. Whether a button is
        // held comes from the caller, since the mouse handler's own isLeftPressed and the like
        // are only kept while no screen is open.
        float[] found = new float[2];
        int part = ImmersiveFrame.NONE;
        if (frameScreen != openScreen(minecraft)) {
            // A mouse event can reach the new screen before its first frame is placed.
            cursorPart = ImmersiveFrame.NONE;
        } else if (!pressed) {
            cursorPart = current.unproject(x, y, ImmersiveFrame.NONE, found);
            part = cursorPart;
        } else if (cursorPart != ImmersiveFrame.NONE) {
            part = current.unproject(x, y, cursorPart, found);
        }
        if (part == ImmersiveFrame.NONE) {
            position[0] = NOWHERE;
            position[1] = NOWHERE;
            aboveX = 0.0f;
            aboveY = 0.0f;
        } else {
            position[0] = found[0] * windowWidth / current.width();
            position[1] = found[1] * windowHeight / current.height();
            aboveX = x - found[0];
            aboveY = y - found[1];
        }
        return true;
    }

    /** The parts of the layout the current frame floats, for a screen that asks what swung. */
    public static List<ImmersiveLayout.Panel> panels() {
        return frame == null ? List.of() : frame.panels();
    }

    @Nullable
    private static Screen openScreen(Minecraft minecraft) {
        //? if <26.2 {
        return minecraft.screen;
        //?} else {
        return minecraft.gui.screen();
        //?}
    }

    /** Whether the HUD and the hand are hidden, as F1 hides them (the option up to 26.1, the HUD's own state from 26.2). */
    private static boolean hudHidden(Minecraft minecraft) {
        //? if <26.2 {
        return minecraft.options.hideGui;
        //?} else {
        return minecraft.gui.hud.isHidden();
        //?}
    }

    private static void hideHud(Minecraft minecraft, boolean hidden) {
        //? if <26.2 {
        minecraft.options.hideGui = hidden;
        //?} else {
        if (minecraft.gui.hud.isHidden() != hidden) {
            minecraft.gui.hud.toggle();
        }
        //?}
    }

    private static float ease(float t) {
        return t < 0.5f ? 4.0f * t * t * t : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    /** The way a camera with the game's yaw and pitch looks. */
    private static Vector3f forward(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        return new Vector3f(
                (float) (-Math.sin(yaw) * Math.cos(pitch)),
                (float) -Math.sin(pitch),
                (float) (Math.cos(yaw) * Math.cos(pitch)));
    }

    /** Camera space to the world's directions: x to the right, y up, z against the view. */
    private static Matrix4f basis(Vector3f right, Vector3f up, Vector3f forward) {
        return new Matrix4f(
                right.x, right.y, right.z, 0.0f,
                up.x, up.y, up.z, 0.0f,
                -forward.x, -forward.y, -forward.z, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f);
    }
}
