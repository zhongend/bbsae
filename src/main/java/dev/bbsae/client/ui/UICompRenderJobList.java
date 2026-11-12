package dev.bbsae.client.ui;

import dev.bbsae.comp.CompRenderJob;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Render queue rows, drawn by BBS's own UIList.
 *
 * <p>The preset column shows the module's <b>display name</b> when the panel can resolve it. The
 * raw id ({@code review_h264}, {@code audio_wav}) is an implementation detail; a queue that reads
 * "clip · audio_wav · 编码中" tells the user nothing about what they are waiting for.</p>
 *
 * <p>Motion here is status feedback, not decoration: the queue is a progress list, so "still
 * working" is the one thing worth animating. A breathing rail marks rendering/encoding rows, the
 * row wash follows the cursor on an exponential approach, and a short flash acknowledges a status
 * change. Channel names are cached per job — a queue that updates every tick must not build strings
 * every frame.</p>
 */
public class UICompRenderJobList extends UIList<CompRenderJob>
{
    private static final int PRUNE_EVERY = 60;
    private static final float PRUNE_IDLE = 2F;

    private final Function<String, String> presetLabel;
    private final UiMotion motion = new UiMotion();
    /** job id → {base, hover, select, flash} channel names. Built once per job. */
    private final Map<String, String[]> keys = new HashMap<>();
    private final Map<String, Integer> lastStatus = new HashMap<>();
    private int motionFrames;

    public UICompRenderJobList(Consumer<List<CompRenderJob>> callback)
    {
        this(callback, null);
    }

    public UICompRenderJobList(Consumer<List<CompRenderJob>> callback, Function<String, String> presetLabel)
    {
        super(callback);

        this.presetLabel = presetLabel;
        this.scroll.scrollItemSize = 20;
    }

    @Override
    protected boolean sortElements()
    {
        return false;
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();

        if (++this.motionFrames >= PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(PRUNE_IDLE);

            /* Finished jobs leave the queue; their cached channel names and last status would
             * otherwise outlive every job ever queued in the session. Rebuilding a handful of
             * strings on the next frame is much cheaper than an unbounded map. */
            if (this.keys.size() > 256)
            {
                this.keys.clear();
                this.lastStatus.clear();
            }
        }

        super.render(context);
    }

    private String[] keys(CompRenderJob job)
    {
        String id = job.id == null ? "" : job.id;
        String[] channels = this.keys.get(id);

        if (channels == null)
        {
            channels = new String[] {
                "job:" + id, "job:" + id + ":h", "job:" + id + ":s", "job:" + id + ":f"
            };
            this.keys.put(id, channels);
        }

        return channels;
    }

    @Override
    public void renderListElement(UIContext context, CompRenderJob element, int index, int x, int y,
                                  boolean hover, boolean selected)
    {
        String[] channels = this.keys(element);
        float hov = this.motion.anim(channels[1], hover ? 1F : 0F, 24F);
        float sel = this.motion.anim(channels[2], selected ? 1F : 0F, 22F);
        int accent = BBSSettings.primaryColor() & 0xFFFFFF;
        int height = this.rowHeight();
        float near = Math.max(hov * 0.22F, sel * 0.50F);
        float far = Math.max(hov * 0.03F, sel * 0.08F);

        if (near > 0.001F || far > 0.001F)
        {
            context.batcher.gradientHBox(x, y, x + this.area.w, y + height,
                UiMotion.alpha(accent, near), UiMotion.alpha(accent, far));
        }

        if (sel > 0.01F)
        {
            context.batcher.box(x, y, x + 2, y + height, UiMotion.alpha(accent, sel));
        }

        this.renderElementPart(context, element, index, x, y, hover, selected);
    }

    @Override
    protected void renderElementPart(UIContext context, CompRenderJob element, int index, int x, int y,
                                     boolean hover, boolean selected)
    {
        String[] channels = this.keys(element);
        Integer previous = this.lastStatus.put(channels[0], element.status);

        if (previous != null && previous != element.status)
        {
            this.motion.flash(channels[3]);
        }

        super.renderElementPart(context, element, index, x, y, hover, selected);

        float flash = 1F - this.motion.flash(channels[3], 0.45F);
        float pulse;

        switch (element.status)
        {
            case CompRenderJob.RENDERING -> pulse = 0.55F + 0.45F * this.motion.breathe(1.4F);
            case CompRenderJob.ENCODING -> pulse = 0.55F + 0.45F * this.motion.breathe(1.1F);
            default -> pulse = 1F;
        }

        /* Horizontal status rail down the left edge: the one column a row of text never covers. */
        int color = statusColor(element.status);
        int top = y + 3;
        int bottom = y + this.rowHeight() - 3;

        context.batcher.box(x, top, x + 2, bottom,
            UiMotion.alpha(color, Math.min(1F, pulse + 0.5F * flash)));

        if (flash > 0.01F)
        {
            context.batcher.box(x + 2, y, x + this.area.w, y + 1,
                UiMotion.alpha(color, 0.55F * flash));
        }
    }

    private static int statusColor(int status)
    {
        return switch (status)
        {
            case CompRenderJob.QUEUED -> 0xFF8A94A6;
            case CompRenderJob.RENDERING -> 0xFF4C9AFF;
            case CompRenderJob.ENCODING -> 0xFF4CC7B8;
            case CompRenderJob.DONE -> 0xFF3FBF6F;
            case CompRenderJob.FAILED -> 0xFFE06A6A;
            case CompRenderJob.CANCELLED -> 0xFF7C8798;
            default -> 0xFF8A94A6;
        };
    }

    @Override
    protected String elementToString(UIContext context, int i, CompRenderJob element)
    {
        String status = switch (element.status)
        {
            case CompRenderJob.QUEUED -> "排队中";
            case CompRenderJob.RENDERING -> "渲染中";
            case CompRenderJob.ENCODING -> "编码中";
            case CompRenderJob.DONE -> "已完成";
            case CompRenderJob.FAILED -> "失败";
            case CompRenderJob.CANCELLED -> "已取消";
            default -> "?";
        };

        String preset = this.presetLabel == null ? null : this.presetLabel.apply(element.presetId);

        if (preset == null || preset.isBlank())
        {
            preset = element.presetId;
        }

        String message = element.message == null || element.message.isBlank() ? "" : "  ·  " + element.message;

        return element.outputName + "  ·  " + preset + "  ·  " + status
            + "  " + Math.round(element.progress * 100F) + "%" + message;
    }
}
