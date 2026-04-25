package tytoo.grapheneui.internal.input.keyboard;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GrapheneKeyboardSharedUtilTest {
    @Test
    void resolveLayoutCharacterIgnoresMacFunctionKeyScancode() {
        char character = GrapheneKeyboardSharedUtil.resolveLayoutCharacter(GLFW.GLFW_KEY_F10, 63, 0);

        assertEquals(KeyEvent.CHAR_UNDEFINED, character);
    }

    @Test
    void resolveShiftedLayoutCharacterMapsAzertyNumberRow() {
        assertEquals('1', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_1, '&'));
        assertEquals('2', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_2, (char) 0x00E9));
        assertEquals('3', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_3, '"'));
        assertEquals('4', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_4, '\''));
        assertEquals('5', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_5, '('));
        assertEquals('6', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_6, '-'));
        assertEquals('7', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_7, (char) 0x00E8));
        assertEquals('8', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_8, '_'));
        assertEquals('9', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_9, (char) 0x00E7));
        assertEquals('0', GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_0, (char) 0x00E0));
    }

    @Test
    void resolveShiftedLayoutCharacterIgnoresNonAzertyPairs() {
        char character = GrapheneKeyboardSharedUtil.resolveShiftedLayoutCharacter(GLFW.GLFW_KEY_1, '1');

        assertEquals(KeyEvent.CHAR_UNDEFINED, character);
    }
}
