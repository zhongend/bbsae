package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in motion templates.
 *
 * <p>A template writes real keyframes into the layer's BBS transform/opacity channels at the
 * <b>layer's own in point</b>, not at 0. That is the whole point of a motion preset: it must keep
 * working after a clip is moved or trimmed. Nothing else on the layer (content, effects, masks)
 * is touched, so these are safe to try and undo.</p>
 */
public final class CompMotion
{
    public static final class Template
    {
        public final String id;
        public final String name;
        public final String description;

        Template(String id, String name, String description)
        {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    private static final List<Template> TEMPLATES = new ArrayList<>();

    static
    {
        add("fade_in", "淡入", "0 → 100% 不透明度，0.6 秒");
        add("fade_out", "淡出", "100% → 0 不透明度，0.6 秒（对齐出点）");
        add("slide_left", "从左滑入", "向左偏移 12% 合成宽度并淡入");
        add("slide_right", "从右滑入", "向右偏移 12% 合成宽度并淡入");
        add("slide_up", "从下滑入", "向下偏移 12% 合成高度并淡入");
        add("slide_down", "从上滑入", "向上偏移 12% 合成高度并淡入");
        add("pop", "弹入", "缩放 0.6 → 1.12 → 1，带回弹");
        add("spin_in", "旋转进入", "旋转 -180° 且缩放 0.4 → 1");
        add("pulse", "呼吸缩放", "1 → 1.06 → 1 循环一次");
        add("float", "漂浮", "上下轻微漂浮，适合标题和 UI");
    }

    private CompMotion()
    {}

    private static void add(String id, String name, String description)
    {
        TEMPLATES.add(new Template(id, name, description));
    }

    public static List<Template> all()
    {
        return Collections.unmodifiableList(TEMPLATES);
    }

    public static Template get(String id)
    {
        for (Template template : TEMPLATES)
        {
            if (template.id.equals(id))
            {
                return template;
            }
        }

        return null;
    }

    /**
     * Applies a template. Returns false when the layer has no usable time range (a zero-length
     * clip), because writing keys on top of each other would produce a single hard cut.
     */
    public static boolean apply(CompLayer layer, CompDocument doc, String id)
    {
        if (layer == null || doc == null)
        {
            return false;
        }

        float fps = Math.max(1F, doc.fps);
        float start = Math.max(0F, layer.in);
        float end = Math.max(start + 1F / fps, Math.min(doc.duration, layer.out));
        float length = end - start;

        if (length <= 0.0001F)
        {
            return false;
        }

        KeyframeChannel<Float> opacity = layer.opacityChannel();
        Transform base = layer.transformAt(start * fps).copy();

        switch (id)
        {
            case "fade_in":
                opacity.removeAll();
                opacity.insert(start * fps, 0F);
                opacity.insert(Math.min(end, start + 0.6F) * fps, 1F);
                return true;

            case "fade_out":
                opacity.removeAll();
                opacity.insert(Math.max(start, end - 0.6F) * fps, 1F);
                opacity.insert(end * fps, 0F);
                return true;

            case "slide_left":
            case "slide_right":
            case "slide_up":
            case "slide_down":
            {
                float dx = doc.width * 0.12F;
                float dy = doc.height * 0.12F;
                Transform from = base.copy();

                switch (id)
                {
                    case "slide_left" -> from.translate.x -= dx;
                    case "slide_right" -> from.translate.x += dx;
                    case "slide_up" -> from.translate.y += dy;
                    default -> from.translate.y -= dy;
                }

                layer.clearTransformAxes();
                opacity.removeAll();
                layer.insertTransformPose(start * fps, from);
                layer.insertTransformPose(Math.min(end, start + 0.7F) * fps, base.copy());
                opacity.insert(start * fps, 0F);
                opacity.insert(Math.min(end, start + 0.5F) * fps, 1F);
                return true;
            }

            case "pop":
            {
                float peak = Math.min(end, start + 0.28F);
                float settle = Math.min(end, start + 0.55F);
                Transform big = base.copy();

                big.scale.x *= 1.12F;
                big.scale.y *= 1.12F;

                Transform small = base.copy();

                small.scale.x *= 0.6F;
                small.scale.y *= 0.6F;

                layer.clearTransformAxes();
                opacity.removeAll();
                layer.insertTransformPose(start * fps, small);
                layer.insertTransformPose(peak * fps, big);
                layer.insertTransformPose(settle * fps, base.copy());
                opacity.insert(start * fps, 0F);
                opacity.insert(Math.min(end, start + 0.2F) * fps, 1F);
                return true;
            }

            case "spin_in":
            {
                Transform from = base.copy();

                from.scale.x *= 0.4F;
                from.scale.y *= 0.4F;
                from.rotate.z -= 180F;

                layer.clearTransformAxes();
                opacity.removeAll();
                layer.insertTransformPose(start * fps, from);
                layer.insertTransformPose(Math.min(end, start + 0.75F) * fps, base.copy());
                opacity.insert(start * fps, 0F);
                opacity.insert(Math.min(end, start + 0.35F) * fps, 1F);
                return true;
            }

            case "pulse":
            {
                Transform big = base.copy();

                big.scale.x *= 1.06F;
                big.scale.y *= 1.06F;

                layer.clearTransformAxes();
                layer.insertTransformPose(start * fps, base.copy());
                layer.insertTransformPose((start + length * 0.5F) * fps, big);
                layer.insertTransformPose(end * fps, base.copy());
                return true;
            }

            case "float":
            {
                Transform up = base.copy();

                up.translate.y -= Math.max(4F, doc.height * 0.012F);

                layer.clearTransformAxes();
                layer.insertTransformPose(start * fps, base.copy());
                layer.insertTransformPose((start + length * 0.5F) * fps, up);
                layer.insertTransformPose(end * fps, base.copy());
                return true;
            }

            default:
                return false;
        }
    }
}
