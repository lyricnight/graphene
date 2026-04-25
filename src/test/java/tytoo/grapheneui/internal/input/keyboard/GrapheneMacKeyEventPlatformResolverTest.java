package tytoo.grapheneui.internal.input.keyboard;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GrapheneMacKeyEventPlatformResolverTest {
    private final GrapheneMacKeyEventPlatformResolver resolver = new GrapheneMacKeyEventPlatformResolver();

    @Test
    void sanitizeTextModifiersClearsOptionModifierForTypedText() {
        int modifiers = GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT;

        int sanitizedModifiers = resolver.sanitizeTextModifiers(modifiers, false);

        assertEquals(GLFW.GLFW_MOD_SHIFT, sanitizedModifiers);
    }
}
