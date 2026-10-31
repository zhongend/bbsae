package dev.bbsae.client.ui;

import dev.bbsae.comp.CompRenderJob;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Render queue rows, drawn by BBS's own UIList.
 *
 * <p>The preset column shows the module's <b>display name</b> when the panel can resolve it. The
 * raw id ({@code review_h264}, {@code audio_wav}) is an implementation detail; a queue that reads
 * "clip · audio_wav · 编码中" tells the user nothing about what they are waiting for.</p>
 */
public class UICompRenderJobList extends UIList<CompRenderJob>
{
    private final Function<String, String> presetLabel;

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
