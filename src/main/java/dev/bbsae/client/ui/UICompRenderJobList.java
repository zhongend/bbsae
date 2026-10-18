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
            case CompRenderJob.QUEUED -> "Queued";
            case CompRenderJob.RENDERING -> "Rendering";
            case CompRenderJob.ENCODING -> "Encoding";
            case CompRenderJob.DONE -> "Done";
            case CompRenderJob.FAILED -> "Failed";
            case CompRenderJob.CANCELLED -> "Cancelled";
            default -> "?";
        };

        return element.outputName + "  ·  " + element.presetId + "  ·  " + status + "  " + Math.round(element.progress * 100F) + "%";
    }
}
