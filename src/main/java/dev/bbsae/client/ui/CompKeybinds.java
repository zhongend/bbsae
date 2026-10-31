package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.utils.keys.KeyCodes;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Editable compositor shortcut registry.
 *
 * <p>The old editor hard-coded two mutually exclusive schemes in one 100-line {@code if} tree.
 * Users could not remap a key, and a third-party keyboard layout could not be accommodated. This
 * class turns every command into an id with per-scheme defaults, plus a persistent user override
 * map in {@link CompPrefs}. The panel dispatches actions through ids, so the defaults and the UI
 * are two views of the same table.</p>
 *
 * <p>Matching is exact on the three modifiers: pressing an extra Shift must not fire the unshifted
 * command. This is what makes "Ctrl+S save" and "S snapping" coexist.</p>
 */
public final class CompKeybinds
{
    public static final class Binding
    {
        public final int key;
        public final boolean ctrl;
        public final boolean shift;
        public final boolean alt;

        public Binding(int key, boolean ctrl, boolean shift, boolean alt)
        {
            this.key = key;
            this.ctrl = ctrl;
            this.shift = shift;
            this.alt = alt;
        }

        public boolean matches(int key, boolean ctrl, boolean shift, boolean alt)
        {
            return this.key == key && this.ctrl == ctrl && this.shift == shift && this.alt == alt;
        }

        public String display()
        {
            StringBuilder text = new StringBuilder();

            if (this.ctrl) text.append("Ctrl + ");
            if (this.shift) text.append("Shift + ");
            if (this.alt) text.append("Alt + ");
            text.append(KeyCodes.getName(this.key));

            return text.toString();
        }
    }

    public static final class Action
    {
        public final String id;
        public final String label;
        public final String category;
        public final List<Binding> premiere = new ArrayList<>();
        public final List<Binding> bbs = new ArrayList<>();

        Action(String id, String label, String category)
        {
            this.id = id;
            this.label = label;
            this.category = category;
        }

        Action both(int key, boolean ctrl, boolean shift, boolean alt)
        {
            this.premiere.add(new Binding(key, ctrl, shift, alt));
            this.bbs.add(new Binding(key, ctrl, shift, alt));

            return this;
        }

        Action premiere(int key, boolean ctrl, boolean shift, boolean alt)
        {
            this.premiere.add(new Binding(key, ctrl, shift, alt));

            return this;
        }

        Action bbs(int key, boolean ctrl, boolean shift, boolean alt)
        {
            this.bbs.add(new Binding(key, ctrl, shift, alt));

            return this;
        }
    }

    private static final List<Action> ACTIONS = new ArrayList<>();
    private static String capturing;
    private static Runnable captureDone;

    private CompKeybinds()
    {}

    private static Binding b(int key, boolean ctrl, boolean shift, boolean alt)
    {
        return new Binding(key, ctrl, shift, alt);
    }

    private static Action action(String id, String label, String category)
    {
        Action action = new Action(id, label, category);

        ACTIONS.add(action);

        return action;
    }

    static
    {
        action("play.pause", "播放 / 暂停", "播放").both(GLFW.GLFW_KEY_SPACE, false, false, false);
        action("play.back", "倒放 / 加速倒放", "播放").both(GLFW.GLFW_KEY_J, false, false, false);
        action("play.forward", "正放 / 加速正放", "播放").both(GLFW.GLFW_KEY_L, false, false, false);
        action("play.stop", "暂停（K）", "播放").both(GLFW.GLFW_KEY_K, false, false, false);

        action("key.transform", "在播放头加变换关键帧", "关键帧")
            .both(GLFW.GLFW_KEY_K, false, false, true);
        action("key.prev", "上一个关键帧", "关键帧").both(GLFW.GLFW_KEY_COMMA, false, false, false);
        action("key.next", "下一个关键帧", "关键帧").both(GLFW.GLFW_KEY_PERIOD, false, false, false);

        action("clip.split", "在播放头拆分片段", "时间轴").both(GLFW.GLFW_KEY_C, false, false, false)
            .both(GLFW.GLFW_KEY_K, true, false, false);
        action("clip.trimIn", "裁剪入点到播放头", "时间轴").both(GLFW.GLFW_KEY_LEFT_BRACKET, false, false, false);
        action("clip.trimOut", "裁剪出点到播放头", "时间轴").both(GLFW.GLFW_KEY_RIGHT_BRACKET, false, false, false);
        action("clip.trimOutAlt", "裁剪出点到播放头（E）", "时间轴")
            .premiere(GLFW.GLFW_KEY_E, false, false, false);

        action("timeline.snap", "开关时间轴吸附", "时间轴").both(GLFW.GLFW_KEY_S, false, false, false);
        action("timeline.fit", "时间轴适配全长", "时间轴").both(GLFW.GLFW_KEY_BACKSLASH, false, false, false);
        action("timeline.zoomIn", "放大时间轴", "时间轴")
            .both(GLFW.GLFW_KEY_EQUAL, false, false, false)
            .both(GLFW.GLFW_KEY_KP_ADD, false, false, false);
        action("timeline.zoomOut", "缩小时间轴", "时间轴")
            .both(GLFW.GLFW_KEY_MINUS, false, false, false)
            .both(GLFW.GLFW_KEY_KP_SUBTRACT, false, false, false);
        action("track.rowUp", "增加轨道行高", "时间轴")
            .both(GLFW.GLFW_KEY_EQUAL, false, false, true);
        action("track.rowDown", "减少轨道行高", "时间轴")
            .both(GLFW.GLFW_KEY_MINUS, false, false, true);

        action("work.in", "设置工作区入点", "工作区")
            .premiere(GLFW.GLFW_KEY_I, false, false, true)
            .bbs(GLFW.GLFW_KEY_I, false, false, false);
        action("work.out", "设置工作区出点", "工作区")
            .premiere(GLFW.GLFW_KEY_O, false, false, true)
            .bbs(GLFW.GLFW_KEY_O, false, false, false);
        action("work.clearIn", "清除工作区入点", "工作区")
            .bbs(GLFW.GLFW_KEY_I, true, true, false);
        action("work.clearOut", "清除工作区出点", "工作区")
            .bbs(GLFW.GLFW_KEY_O, true, true, false);

        action("marker.add", "在播放头添加标记", "标记").both(GLFW.GLFW_KEY_M, false, false, false);
        action("marker.next", "下一个标记", "标记").both(GLFW.GLFW_KEY_M, false, true, false);
        action("marker.prev", "上一个标记", "标记").both(GLFW.GLFW_KEY_M, true, true, false);

        action("go.start", "跳到合成起点", "导航").both(GLFW.GLFW_KEY_HOME, false, false, false);
        action("go.end", "跳到合成终点", "导航").both(GLFW.GLFW_KEY_END, false, false, false);

        action("selection.delete", "删除所选图层", "编辑")
            .both(GLFW.GLFW_KEY_DELETE, false, false, false)
            .both(GLFW.GLFW_KEY_BACKSPACE, false, false, false);
        action("edit.undo", "撤销", "编辑").both(GLFW.GLFW_KEY_Z, true, false, false);
        action("edit.redo", "重做", "编辑")
            .both(GLFW.GLFW_KEY_Z, true, true, false)
            .both(GLFW.GLFW_KEY_Y, true, false, false);
        action("layer.duplicate", "复制所选图层", "编辑")
            .premiere(GLFW.GLFW_KEY_D, true, true, false)
            .bbs(GLFW.GLFW_KEY_D, true, false, false);
        action("select.all", "全选图层", "编辑").both(GLFW.GLFW_KEY_A, true, true, false);
        action("tool.select", "选择工具提示", "编辑")
            .both(GLFW.GLFW_KEY_V, false, false, false)
            .both(GLFW.GLFW_KEY_A, false, false, false);

        action("file.save", "保存工程", "文件").both(GLFW.GLFW_KEY_S, true, false, false);
        action("file.export", "打开导出对话框", "文件").both(GLFW.GLFW_KEY_M, true, false, false);

        action("view.focus", "切换专注模式", "视图").both(GLFW.GLFW_KEY_F, false, false, false);
        action("settings.open", "打开合成器设置", "视图").both(GLFW.GLFW_KEY_COMMA, true, false, false);
        action("help.shortcuts", "打开快捷键编辑", "帮助")
            .both(GLFW.GLFW_KEY_SLASH, true, false, false)
            .both(GLFW.GLFW_KEY_K, true, false, true);
    }

    public static List<Action> actions()
    {
        return ACTIONS;
    }

    public static Set<String> categories()
    {
        LinkedHashSet<String> categories = new LinkedHashSet<>();

        for (Action action : ACTIONS)
        {
            categories.add(action.category);
        }

        return categories;
    }

    public static Action byId(String id)
    {
        for (Action action : ACTIONS)
        {
            if (action.id.equals(id))
            {
                return action;
            }
        }

        return null;
    }

    private static CompPrefs.KeyData override(Action action)
    {
        return CompPrefs.get().keybinds.get(action.id);
    }

    public static List<Binding> defaults(Action action, int scheme)
    {
        return scheme == 1 ? action.bbs : action.premiere;
    }

    public static Binding primary(Action action, int scheme)
    {
        CompPrefs.KeyData data = override(action);

        if (data != null)
        {
            return data.cleared ? null : new Binding(data.key, data.ctrl, data.shift, data.alt);
        }

        List<Binding> list = defaults(action, scheme);

        return list.isEmpty() ? null : list.get(0);
    }

    public static String display(Action action, int scheme)
    {
        Binding binding = primary(action, scheme);

        return binding == null ? "未绑定" : binding.display();
    }

    /** Returns the label of another action already using this exact combination. */
    public static String conflict(Action action, Binding binding, int scheme)
    {
        if (binding == null)
        {
            return null;
        }

        for (Action other : ACTIONS)
        {
            if (other == action)
            {
                continue;
            }

            CompPrefs.KeyData data = override(other);
            boolean used;

            if (data != null)
            {
                used = !data.cleared && new Binding(data.key, data.ctrl, data.shift, data.alt)
                    .matches(binding.key, binding.ctrl, binding.shift, binding.alt);
            }
            else
            {
                used = false;

                for (Binding candidate : defaults(other, scheme))
                {
                    if (candidate.matches(binding.key, binding.ctrl, binding.shift, binding.alt))
                    {
                        used = true;
                        break;
                    }
                }
            }

            if (used)
            {
                return other.label;
            }
        }

        return null;
    }

    public static boolean isCustom(Action action)
    {
        return CompPrefs.get().keybinds.containsKey(action.id);
    }

    public static String find(int key, boolean ctrl, boolean shift, boolean alt, int scheme)
    {
        for (Action action : ACTIONS)
        {
            CompPrefs.KeyData data = override(action);

            if (data != null)
            {
                if (!data.cleared && new Binding(data.key, data.ctrl, data.shift, data.alt).matches(key, ctrl, shift, alt))
                {
                    return action.id;
                }

                continue;
            }

            for (Binding binding : defaults(action, scheme))
            {
                if (binding.matches(key, ctrl, shift, alt))
                {
                    return action.id;
                }
            }
        }

        return null;
    }

    public static void beginCapture(String actionId, Runnable done)
    {
        capturing = actionId;
        captureDone = done;
    }

    public static boolean isCapturing()
    {
        return capturing != null;
    }

    public static String capturingAction()
    {
        return capturing;
    }

    public static void cancelCapture()
    {
        capturing = null;
        captureDone = null;
    }

    /** Returns true when the key was consumed by the capture. */
    public static boolean capture(int key, boolean ctrl, boolean shift, boolean alt)
    {
        if (capturing == null)
        {
            return false;
        }

        if (key == GLFW.GLFW_KEY_ESCAPE)
        {
            finishCapture();

            return true;
        }

        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE)
        {
            CompPrefs.KeyData data = new CompPrefs.KeyData();

            data.cleared = true;
            CompPrefs.get().keybinds.put(capturing, data);
            CompPrefs.save();
            finishCapture();

            return true;
        }

        /* Modifier alone is not a binding. */
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
            || key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
            || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT
            || key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER)
        {
            return true;
        }

        CompPrefs.get().keybinds.put(capturing, new CompPrefs.KeyData(key, ctrl, shift, alt));
        CompPrefs.save();
        finishCapture();

        return true;
    }

    private static void finishCapture()
    {
        Runnable done = captureDone;

        capturing = null;
        captureDone = null;

        if (done != null)
        {
            done.run();
        }
    }

    public static void reset(Action action)
    {
        CompPrefs.get().keybinds.remove(action.id);
        CompPrefs.save();
    }

    public static void resetAll()
    {
        CompPrefs.get().keybinds.clear();
        CompPrefs.save();
    }

    /** Scheme display name for the settings/onboarding UI. */
    public static String schemeName(int scheme)
    {
        return scheme == 1 ? "BBS 原版" : "Premiere";
    }
}
