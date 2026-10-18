package dev.bbsae.client.ui;

import mchorse.bbs_mod.graphics.window.Window;
import org.lwjgl.glfw.GLFW;

/**
 * Modifier state read straight from GLFW.
 *
 * <p>{@link Window#isAltPressed()} goes through {@code Screen.hasAltDown()}, which only reflects
 * keys the vanilla screen has seen. Inside a BBS dashboard panel that is not reliable, and it was
 * the reason Alt+wheel appeared dead. Reading the physical key state is the single source of
 * truth for every compositor gesture.</p>
 */
final class CompInput
{
    private CompInput()
    {}

    static boolean altDown()
    {
        long handle = Window.getWindow();

        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS
            || Window.isAltPressed();
    }

    static boolean ctrlDown()
    {
        long handle = Window.getWindow();

        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
            || Window.isCtrlPressed();
    }

    static boolean shiftDown()
    {
        long handle = Window.getWindow();

        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
            || Window.isShiftPressed();
    }
}
