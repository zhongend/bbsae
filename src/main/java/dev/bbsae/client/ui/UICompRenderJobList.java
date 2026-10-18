package dev.bbsae.client.ui;

import dev.bbsae.comp.CompRenderJob;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;

import java.util.List;
import java.util.function.Consumer;

/** Render queue rows, drawn by BBS's own UIList. */
public class UICompRenderJobList extends UIList<CompRenderJob>
{
    public UICompRenderJobList(Consumer<List<CompRenderJob>> callback)
    {
        super(callback);

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

        return element.outputName + "  ·  " + element.presetId + "  ·  " + status + "  " + Math.round(element.progress * 100F) + "%";
    }
}
