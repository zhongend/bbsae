package dev.bbsae.client.replay;

import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.framework.UIContext;

import java.util.function.Supplier;

/**
 * The replay page's copy of BBS' replay-root transform, with a UI context.
 *
 * <p>{@link UIReplayPropTransform} is an ordinary UI element: its {@code getContext()} walks to the
 * screen root, and a gesture only starts when that context exists. The replay page owns this copy
 * instead of placing it in the film panel's widget tree, so it was detached and its context was
 * always {@code null}. {@code Gizmo.start()} still returned {@code true} (the stencil had picked a
 * handle), the page showed a dragging gizmo, and every mouse move did nothing — the reported
 * "transform handles cannot move the replay".</p>
 *
 * <p>The transform deliberately reports itself invisible: it has no fields to draw, and
 * {@code GizmoInteraction} then pumps the live gesture every frame
 * ({@code TransformGesture#pumpIfHidden}). Rendering it as well would pump twice.</p>
 */
public final class ReplaySessionTransform extends UIReplayPropTransform
{
    private final Supplier<UIContext> contexts;

    public ReplaySessionTransform(Supplier<UIContext> contexts)
    {
        this.contexts = contexts;
    }

    @Override
    public UIContext getContext()
    {
        UIContext context = this.contexts == null ? null : this.contexts.get();

        return context != null ? context : super.getContext();
    }

    @Override
    public boolean isVisible()
    {
        return false;
    }
}
