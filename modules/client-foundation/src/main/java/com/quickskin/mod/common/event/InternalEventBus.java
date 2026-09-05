package com.quickskin.mod.common.event;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Simple internal event bus for service-to-service communication
 * This is NOT the Architectury event system - it's for internal mod events
 */
@Environment(EnvType.CLIENT)
public class InternalEventBus {
    private static final InternalEventBus INSTANCE = new InternalEventBus();

    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    /** Creates an isolated bus for tests or an explicitly owned client runtime. */
    public InternalEventBus() {}

    public static InternalEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a listener for a specific event type
     * @param eventType The event class
     * @param listener The listener to register
     * @param <T> The event type
     */
    public <T> Subscription register(Class<T> eventType, Consumer<T> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");

        CopyOnWriteArrayList<Consumer<?>> eventListeners =
                listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
        eventListeners.add(listener);

        AtomicBoolean subscribed = new AtomicBoolean(true);
        return () -> {
            if (!subscribed.compareAndSet(true, false)) {
                return;
            }
            eventListeners.remove(listener);
            if (eventListeners.isEmpty()) {
                listeners.remove(eventType, eventListeners);
            }
        };
    }

    /**
     * Posts an event to all registered listeners
     * @param event The event to post
     * @param <T> The event type
     */
    @SuppressWarnings("unchecked")
    public <T> void post(T event) {
        Objects.requireNonNull(event, "event");
        Class<?> eventType = event.getClass();
        List<Consumer<?>> eventListeners = listeners.get(eventType);

        if (eventListeners != null && !eventListeners.isEmpty()) {
            for (Consumer<?> listener : eventListeners) {
                try {
                    ((Consumer<T>) listener).accept(event);
                } catch (RuntimeException | LinkageError error) {
                    QuickSkinInfo.LOGGER.error("QuickSkin event listener failed for {}", eventType.getName(), error);
                }
            }
        }
    }

    /** Removes every internal listener, primarily for explicit client shutdown. */
    public void clear() {
        listeners.clear();
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
