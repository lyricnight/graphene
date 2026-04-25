package tytoo.grapheneui.internal.browser;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import tytoo.grapheneui.api.bridge.GrapheneBridge;
import tytoo.grapheneui.internal.input.GrapheneInputModifierUtil;
import tytoo.grapheneui.internal.input.mouse.GrapheneMouseButtonUtil;

import java.awt.*;
import java.util.Objects;

public final class GrapheneWebViewInputController {
    private static final int MAX_CLICK_COUNT = 3;
    private static final int WHEEL_DELTA_PER_STEP = 120;
    private static final double ZOOM_LEVEL_STEP = 0.2D;
    private static final double MIN_ZOOM_LEVEL = -10.0D;
    private static final double MAX_ZOOM_LEVEL = 10.0D;
    private static final String MOUSE_EXTRA_BUTTON_CHANNEL = "graphene:mouse:extra-button";
    private static final String MOUSE_EXTRA_RESET_CHANNEL = "graphene:mouse:extra-reset";

    private final GrapheneBrowser browser;
    private final GrapheneFocusUtil focusUtil;
    private final GrapheneBridge bridge;
    private int lastBrowserMouseX = Integer.MIN_VALUE;
    private int lastBrowserMouseY = Integer.MIN_VALUE;
    private boolean primaryPointerButtonDown;
    private int lastClickButton = -1;
    private int clickCount;
    private int pressedButton = -1;
    private int pressedClickCount = 1;
    private int pressedButtons;

    public GrapheneWebViewInputController(GrapheneBrowser browser, GrapheneFocusUtil focusUtil, GrapheneBridge bridge) {
        this.browser = Objects.requireNonNull(browser, "browser");
        this.focusUtil = Objects.requireNonNull(focusUtil, "focusUtil");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public boolean isPrimaryPointerButtonDown() {
        return primaryPointerButtonDown;
    }

    public void updateMousePosition(Point browserPoint) {
        if (browserPoint.x == lastBrowserMouseX && browserPoint.y == lastBrowserMouseY) {
            return;
        }

        lastBrowserMouseX = browserPoint.x;
        lastBrowserMouseY = browserPoint.y;
        browser.mouseMoved(browserPoint.x, browserPoint.y, 0);
    }

    public void onMouseClicked(int button, boolean isDoubleClick, Point browserPoint) {
        primaryPointerButtonDown = button == 0;
        int currentClickCount = resolveClickCount(button, isDoubleClick);
        pressedButton = button;
        pressedClickCount = currentClickCount;
        pressedButtons |= GrapheneMouseButtonUtil.toDevToolsButtonsBit(button);
        int modifiers = GrapheneInputModifierUtil.currentModifiers();
        if (GrapheneMouseButtonUtil.isBrowserNavigationButton(button)) {
            browser.navigationButtonInteracted(browserPoint.x, browserPoint.y, modifiers, button, true, currentClickCount, pressedButtons);
            return;
        }

        if (GrapheneMouseButtonUtil.isExtraMouseButton(button)) {
            emitExtraMouseButtonEvent(button, true);
            return;
        }

        browser.mouseInteracted(browserPoint.x, browserPoint.y, modifiers, button, true, currentClickCount);
    }

    public boolean onMouseReleased(int button, Point browserPoint) {
        if (button == 0) {
            primaryPointerButtonDown = false;
        }

        pressedButtons &= ~GrapheneMouseButtonUtil.toDevToolsButtonsBit(button);

        int modifiers = GrapheneInputModifierUtil.currentModifiers();
        int cefModifiers = GrapheneInputModifierUtil.toCefCommonModifiers(modifiers);

        if (!focusUtil.isFocused()) {
            if (button == 0) {
                browser.cancelActiveDrag();
            }
            return false;
        }

        int releaseClickCount = button == pressedButton ? pressedClickCount : 1;
        if (GrapheneMouseButtonUtil.isBrowserNavigationButton(button)) {
            browser.navigationButtonInteracted(browserPoint.x, browserPoint.y, modifiers, button, false, releaseClickCount, pressedButtons);
        } else if (GrapheneMouseButtonUtil.isExtraMouseButton(button)) {
            emitExtraMouseButtonEvent(button, false);
        } else {
            browser.mouseInteracted(browserPoint.x, browserPoint.y, modifiers, button, false, releaseClickCount);
            if (button == 0) {
                browser.dragCompleted(browserPoint.x, browserPoint.y, cefModifiers);
            }
        }

        if (button == pressedButton) {
            pressedButton = -1;
            pressedClickCount = 1;
        }

        return true;
    }

    public boolean onMouseDragged(int button, Point browserPoint) {
        if (!focusUtil.isFocused()) {
            return false;
        }

        lastBrowserMouseX = browserPoint.x;
        lastBrowserMouseY = browserPoint.y;
        browser.mouseDragged(browserPoint.x, browserPoint.y, button);
        browser.dragUpdated(browserPoint.x, browserPoint.y, currentCefModifiers());
        return true;
    }

    public void onMouseScrolled(Point browserPoint, int delta, int rotation) {
        int modifiers = GrapheneInputModifierUtil.currentModifiers();
        int wheelDelta = delta * rotation;
        if (GrapheneInputModifierUtil.isEditShortcutModifierDown(modifiers) && wheelDelta != 0) {
            applyZoomDelta(wheelDelta);
            return;
        }

        browser.mouseScrolled(browserPoint.x, browserPoint.y, modifiers, delta, rotation);
    }

    public void onKeyPressed(KeyEvent keyEvent) {
        browser.keyPressed(keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers());
    }

    public void onKeyReleased(KeyEvent keyEvent) {
        browser.keyReleased(keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers());
    }

    public void onCharacterTyped(CharacterEvent characterEvent) {
        browser.textInput(new String(Character.toChars(characterEvent.codepoint())));
    }

    public void onFocusChanged(boolean focused) {
        if (focused) {
            return;
        }

        browser.cancelActiveDrag();
        browser.resetKeyboardState();
        resetExtraMouseButtons();
        primaryPointerButtonDown = false;
        pressedButton = -1;
        pressedClickCount = 1;
        pressedButtons = 0;
    }

    private int resolveClickCount(int button, boolean isDoubleClick) {
        if (!isDoubleClick || button != lastClickButton) {
            clickCount = 1;
        } else {
            clickCount = Math.min(clickCount + 1, MAX_CLICK_COUNT);
        }

        lastClickButton = button;
        return clickCount;
    }

    private void applyZoomDelta(int wheelDelta) {
        int stepCount = Math.max(1, Math.abs(wheelDelta) / WHEEL_DELTA_PER_STEP);
        double direction = Math.signum(wheelDelta);
        double currentZoomLevel = browser.getZoomLevel();
        double nextZoomLevel = clampZoomLevel(currentZoomLevel + direction * ZOOM_LEVEL_STEP * stepCount);
        if (Double.compare(nextZoomLevel, currentZoomLevel) == 0) {
            return;
        }

        browser.setZoomLevel(nextZoomLevel);
    }

    private double clampZoomLevel(double zoomLevel) {
        return Math.clamp(zoomLevel, MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL);
    }

    private void emitExtraMouseButtonEvent(int button, boolean pressed) {
        if (!GrapheneMouseButtonUtil.isExtraMouseButton(button)) {
            return;
        }

        try {
            bridge.emit(MOUSE_EXTRA_BUTTON_CHANNEL, extraMouseButtonPayload(button, pressed));
        } catch (IllegalStateException ignored) {
            // Ignore events while the bridge is shutting down.
        }
    }

    private String extraMouseButtonPayload(int button, boolean pressed) {
        return "{\"button\":" + button + ",\"pressed\":" + pressed + "}";
    }

    private void resetExtraMouseButtons() {
        try {
            bridge.emit(MOUSE_EXTRA_RESET_CHANNEL, "{}");
        } catch (IllegalStateException ignored) {
            // Ignore events while the bridge is shutting down.
        }
    }

    private int currentCefModifiers() {
        return GrapheneInputModifierUtil.toCefCommonModifiers(GrapheneInputModifierUtil.currentModifiers());
    }
}
