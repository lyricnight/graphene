package tytoo.grapheneui.internal.browser;

import com.google.gson.JsonObject;
import tytoo.grapheneui.internal.input.GrapheneInputModifierUtil;
import tytoo.grapheneui.internal.input.keyboard.GrapheneDomKeyData;
import tytoo.grapheneui.internal.input.keyboard.GrapheneDomKeyboardMapper;
import tytoo.grapheneui.internal.input.keyboard.GrapheneInputLockState;
import tytoo.grapheneui.internal.logging.GrapheneDebugLogger;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class GrapheneDomKeyboardDispatcher {
    private static final long SYNTHETIC_TYPED_DUPLICATE_WINDOW_MS = 250L;
    private static final String DEVTOOLS_METHOD_DISPATCH_KEY_EVENT = "Input.dispatchKeyEvent";
    private static final String DEVTOOLS_METHOD_INSERT_TEXT = "Input.insertText";
    private static final String KEY_EVENT_TYPE_RAW_KEY_DOWN = "rawKeyDown";
    private static final String KEY_EVENT_TYPE_KEY_UP = "keyUp";
    private static final String PROPERTY_TYPE = "type";
    private static final String PROPERTY_MODIFIERS = "modifiers";
    private static final String PROPERTY_CODE = "code";
    private static final String PROPERTY_KEY = "key";
    private static final String PROPERTY_TEXT = "text";
    private static final String PROPERTY_UNMODIFIED_TEXT = "unmodifiedText";
    private static final String PROPERTY_WINDOWS_VIRTUAL_KEY_CODE = "windowsVirtualKeyCode";
    private static final String PROPERTY_NATIVE_VIRTUAL_KEY_CODE = "nativeVirtualKeyCode";
    private static final String PROPERTY_AUTO_REPEAT = "autoRepeat";
    private static final String PROPERTY_IS_KEYPAD = "isKeypad";
    private static final String PROPERTY_IS_SYSTEM_KEY = "isSystemKey";
    private static final String PROPERTY_LOCATION = "location";
    private static final GrapheneDebugLogger DEBUG_LOGGER = GrapheneDebugLogger.of(GrapheneDomKeyboardDispatcher.class);

    private final GrapheneDevToolsMethodExecutor devToolsMethodExecutor;
    private final GrapheneDomKeyboardMapper keyboardMapper = new GrapheneDomKeyboardMapper();
    private final GrapheneInputLockState lockState = new GrapheneInputLockState();
    private final Set<Integer> pressedKeys = new HashSet<>();

    private String pendingSyntheticText = "";
    private long pendingSyntheticTextTimestamp;

    GrapheneDomKeyboardDispatcher(GrapheneBrowser browser) {
        this.devToolsMethodExecutor = new GrapheneDevToolsMethodExecutor(Objects.requireNonNull(browser, "browser"));
    }

    void keyPressed(int keyCode, int scanCode, int modifiers) {
        lockState.ensureLockKeyModifiersEnabled();
        int resolvedModifiers = GrapheneInputModifierUtil.mergeWithCurrentModifiers(modifiers);
        lockState.updateCachedNumLockState(keyCode, true);
        boolean numLockEnabled = lockState.isNumLockEnabled(resolvedModifiers);
        GrapheneDomKeyData keyData = keyboardMapper.mapKeyEvent(keyCode, scanCode, resolvedModifiers, true, numLockEnabled);
        boolean autoRepeat = !pressedKeys.add(keyCode);
        dispatchKeyEvent(KEY_EVENT_TYPE_RAW_KEY_DOWN, keyData, autoRepeat);

        String syntheticText = keyboardMapper.resolveSyntheticText(keyCode, keyData, numLockEnabled);
        if (!syntheticText.isEmpty()) {
            rememberSyntheticText(syntheticText);
            dispatchInsertText(syntheticText);
        }
    }

    void keyReleased(int keyCode, int scanCode, int modifiers) {
        lockState.ensureLockKeyModifiersEnabled();
        int resolvedModifiers = GrapheneInputModifierUtil.mergeWithCurrentModifiers(modifiers);
        pressedKeys.remove(keyCode);
        boolean numLockEnabled = lockState.isNumLockEnabled(resolvedModifiers);
        GrapheneDomKeyData keyData = keyboardMapper.mapKeyEvent(keyCode, scanCode, resolvedModifiers, false, numLockEnabled);
        dispatchKeyEvent(KEY_EVENT_TYPE_KEY_UP, keyData, false);
    }

    void textInput(String text) {
        lockState.ensureLockKeyModifiersEnabled();
        String normalizedText = keyboardMapper.normalizeTypedText(text);
        if (normalizedText.isEmpty() || isDuplicateSyntheticText(normalizedText)) {
            return;
        }

        if (shouldLetKeyEventHandleText(normalizedText)) {
            return;
        }

        dispatchInsertText(normalizedText);
    }

    void resetState() {
        pressedKeys.clear();
        pendingSyntheticText = "";
        pendingSyntheticTextTimestamp = 0L;
    }

    private void dispatchKeyEvent(String type, GrapheneDomKeyData keyData, boolean autoRepeat) {
        JsonObject payload = new JsonObject();
        payload.addProperty(PROPERTY_TYPE, type);
        payload.addProperty(PROPERTY_MODIFIERS, keyData.modifiers());
        appendKeyDataProperties(payload, keyData);
        if (KEY_EVENT_TYPE_RAW_KEY_DOWN.equals(type)) {
            payload.addProperty(PROPERTY_TEXT, "");
            payload.addProperty(PROPERTY_UNMODIFIED_TEXT, "");
        }
        payload.addProperty(PROPERTY_AUTO_REPEAT, autoRepeat);
        payload.addProperty(PROPERTY_IS_SYSTEM_KEY, keyData.systemKey());
        executeDevToolsMethod(DEVTOOLS_METHOD_DISPATCH_KEY_EVENT, payload);
    }

    private void dispatchInsertText(String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty(PROPERTY_TEXT, text);
        executeDevToolsMethod(DEVTOOLS_METHOD_INSERT_TEXT, payload);
    }

    private boolean shouldLetKeyEventHandleText(String text) {
        if (text.codePointCount(0, text.length()) != 1) {
            return false;
        }

        int codePoint = text.codePointAt(0);
        return Character.isISOControl(codePoint);
    }

    private void appendKeyDataProperties(JsonObject payload, GrapheneDomKeyData keyData) {
        if (!keyData.code().isEmpty()) {
            payload.addProperty(PROPERTY_CODE, keyData.code());
        }
        payload.addProperty(PROPERTY_KEY, keyData.key());
        payload.addProperty(PROPERTY_WINDOWS_VIRTUAL_KEY_CODE, keyData.windowsVirtualKeyCode());
        payload.addProperty(PROPERTY_NATIVE_VIRTUAL_KEY_CODE, keyData.nativeVirtualKeyCode());
        payload.addProperty(PROPERTY_IS_KEYPAD, keyData.keypad());
        payload.addProperty(PROPERTY_LOCATION, keyData.location());
    }

    private void executeDevToolsMethod(String method, JsonObject payload) {
        devToolsMethodExecutor.executeMethod(method, payload, DEBUG_LOGGER);
    }

    private void rememberSyntheticText(String text) {
        pendingSyntheticText = text;
        pendingSyntheticTextTimestamp = System.currentTimeMillis();
    }

    private boolean isDuplicateSyntheticText(String text) {
        if (pendingSyntheticText.isEmpty()) {
            return false;
        }

        long now = System.currentTimeMillis();
        boolean duplicate = now - pendingSyntheticTextTimestamp <= SYNTHETIC_TYPED_DUPLICATE_WINDOW_MS
                && pendingSyntheticText.equals(text);
        pendingSyntheticText = "";
        pendingSyntheticTextTimestamp = 0L;
        return duplicate;
    }
}
