package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompTracker;
import dev.bbsae.comp.TrackerMatcher;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Drives one {@link CompTracker} through the composition's frames, one frame per call.
 *
 * <p>The engine is cooperative: the compositor panel calls {@link #update()} once per UI frame,
 * and each call renders exactly one composition frame through {@link CompFrameServer} (the same
 * deterministic frame function the AE encoder uses), reads the search region back from the GPU and
 * runs {@link TrackerMatcher}. A 12 s @ 60 fps composition therefore takes ~720 editor frames to
 * analyze, with the UI staying responsive the whole time and the status message reporting
 * progress.</p>
 *
 * <p>Template maintenance follows the feature: each analyzed frame captures a fresh pattern at the
 * matched position, so slow appearance changes (lighting, scale) keep tracking; a flat or
 * low-contrast neighbourhood collapses the NCC score and stops the run with a readable message
 * instead of drifting off silently.</p>
 */
public final class TrackerEngine
{
    /** Seek first when the composition samples the live world, so the world frame matches the frame. */
    private static final int PHASE_SEEK = 0;
    private static final int PHASE_RENDER = 1;

    private static CompProject project;
    private static CompDocument document;
    private static CompTracker tracker;
    private static float[] template;
    private static int templateWidth;
    private static int templateHeight;
    private static float lastX;
    private static float lastY;
    private static int phase = PHASE_RENDER;

    private TrackerEngine()
    {}

    public static boolean isRunning()
    {
        return tracker != null && tracker.state == CompTracker.STATE_TRACKING;
    }

    public static CompTracker current()
    {
        return tracker;
    }

    public static void start(CompProject compProject, CompDocument doc, CompTracker target)
    {
        if (compProject == null || doc == null || target == null)
        {
            return;
        }

        target.clearSamples();
        target.state = CompTracker.STATE_TRACKING;
        target.message = "";
        target.cursorFrame = Math.max(0, Math.min(target.startFrame, target.endFrame));

        project = compProject;
        document = doc;
        tracker = target;
        template = null;
        lastX = target.x;
        lastY = target.y;
        phase = CompPrecache.dependsOnLiveWorld(doc) ? PHASE_SEEK : PHASE_RENDER;
    }

    public static void cancel()
    {
        if (tracker != null && tracker.state == CompTracker.STATE_TRACKING)
        {
            tracker.state = CompTracker.STATE_IDLE;
            tracker.message = "已取消";
        }

        tracker = null;
        template = null;
        phase = PHASE_RENDER;
    }

    /** Analyzes at most one frame; safe to call every display frame. */
    public static void update()
    {
        if (!isRunning() || project == null || document == null)
        {
            return;
        }

        /* The tracker was deleted mid-run (or a different document became active): stop instead of
         * writing samples into an orphan. */
        if (document.getTracker(tracker.id) != tracker)
        {
            cancel();

            return;
        }

        CompTracker target = tracker;
        int frame = target.cursorFrame;

        if (frame > target.endFrame)
        {
            finish(target);

            return;
        }

        /* A composition with a BBS world / entity layer renders the live film frame. The runner is
         * synced to the analysis frame here, and the world render + LAST capture that happen before
         * the NEXT display frame then show exactly that instant — so each analyzed frame costs one
         * editor frame of seeking first. */
        if (phase == PHASE_SEEK)
        {
            dev.bbsae.client.overlay.BbsPreviewBridge.sync(frame / Math.max(1F, document.fps));
            phase = PHASE_RENDER;

            return;
        }

        int patternHalf = Math.max(4, Math.min(64, target.pattern));
        int searchHalf = Math.max(patternHalf, Math.min(128, target.search));

        /* Direct render, deliberately NOT through the frame server: its signature does not carry
         * the live world state, and a tracker is a one-shot pass anyway. The framebuffer is pooled
         * and released right after the region read. */
        GlFramebuffer framebuffer = CompRenderer.renderFrame(project, document, frame, false, 1F);

        if (framebuffer == null)
        {
            fail(target, "无法渲染第 " + frame + " 帧");

            return;
        }

        int frameWidth = framebuffer.width;
        int frameHeight = framebuffer.height;

        /* Search window around the last known position, clamped to the frame. */
        int x0 = Math.max(0, Math.round(lastX - searchHalf - patternHalf));
        int y0 = Math.max(0, Math.round(lastY - searchHalf - patternHalf));
        int x1 = Math.min(frameWidth, Math.round(lastX + searchHalf + patternHalf) + 1);
        int y1 = Math.min(frameHeight, Math.round(lastY + searchHalf + patternHalf) + 1);
        int regionWidth = Math.max(1, x1 - x0);
        int regionHeight = Math.max(1, y1 - y0);

        int[] region;

        try
        {
            region = readRegion(framebuffer, x0, y0, regionWidth, regionHeight);
        }
        finally
        {
            FboPool.release(framebuffer);
        }

        if (region == null)
        {
            fail(target, "读取第 " + frame + " 帧失败");

            return;
        }

        float score;
        float newX;
        float newY;

        if (template == null)
        {
            /* First frame: anchor is the truth, the pattern is what every later frame matches. */
            templateWidth = patternHalf * 2 + 1;
            templateHeight = templateWidth;
            template = TrackerMatcher.window(region, regionWidth, regionHeight,
                Math.round(lastX) - x0, Math.round(lastY) - y0, patternHalf);
            score = 1F;
            newX = lastX;
            newY = lastY;
        }
        else
        {
            /* Match over the whole clamped region — it is centred on the last position and sized
             * (2·search + pattern) per axis, which is exactly the slide window the tracker needs. */
            float[] searchLuma = lumaOf(region);

            TrackerMatcher.Result result = TrackerMatcher.match(template, templateWidth, templateHeight,
                searchLuma, regionWidth, regionHeight);

            score = result.score;

            if (score < target.confidence)
            {
                fail(target, String.format("置信度过低 (%.2f < %.2f)，在第 %d 帧丢失目标", score, target.confidence, frame));

                return;
            }

            newX = Math.max(0F, Math.min(frameWidth - 1F, x0 + result.x));
            newY = Math.max(0F, Math.min(frameHeight - 1F, y0 + result.y));

            template = TrackerMatcher.window(region, regionWidth, regionHeight,
                Math.round(newX) - x0, Math.round(newY) - y0, patternHalf);
        }

        target.putSample(frame, newX, newY, score);
        lastX = newX;
        lastY = newY;
        target.cursorFrame = frame + 1;

        /* A live-world composition needs the runner synced before the next frame can be read. */
        phase = CompPrecache.dependsOnLiveWorld(document) ? PHASE_SEEK : PHASE_RENDER;

        if (target.cursorFrame > target.endFrame)
        {
            finish(target);
        }
    }

    /** Reads a top-down ARGB region out of a GL framebuffer (GL rows are bottom-up). */
    private static int[] readRegion(GlFramebuffer framebuffer, int x, int y, int width, int height)
    {
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4);
        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.id);
            GL11.glReadPixels(x, framebuffer.height - (y + height), width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            int[] pixels = new int[width * height];

            for (int row = 0; row < height; row++)
            {
                int glRow = height - 1 - row;

                for (int col = 0; col < width; col++)
                {
                    int at = (col + glRow * width) * 4;
                    int r = buffer.get(at) & 0xFF;
                    int g = buffer.get(at + 1) & 0xFF;
                    int b = buffer.get(at + 2) & 0xFF;
                    int a = buffer.get(at + 3) & 0xFF;

                    pixels[col + row * width] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            return pixels;
        }
        catch (RuntimeException e)
        {
            return null;
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            MemoryUtil.memFree(buffer);
        }
    }

    private static float[] lumaOf(int[] argb)
    {
        float[] luma = new float[argb.length];

        for (int i = 0; i < argb.length; i++)
        {
            luma[i] = TrackerMatcher.luma(argb[i]);
        }

        return luma;
    }

    private static void finish(CompTracker target)
    {
        target.state = CompTracker.STATE_DONE;
        target.message = "完成：" + target.samples.size() + " 帧";
        tracker = null;
        template = null;
    }

    private static void fail(CompTracker target, String message)
    {
        target.state = CompTracker.STATE_FAILED;
        target.message = message;
        tracker = null;
        template = null;
    }
}
