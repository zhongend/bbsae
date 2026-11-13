package dev.bbsae.client.replay;

import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.ui.CompPrefs;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Draws the composition's non-actor elements into the replay viewport as an alignment guide.
 *
 * <p>The replay editor is a 3D actor editor, but the shot is composed in the bbsae comp: cameras,
 * text, cards and the comp frame all live in composition pixels. Editing an actor without seeing
 * where the camera and the other layers are is guesswork. This overlay projects those elements into
 * the same free camera as the actors:</p>
 *
 * <ul>
 *   <li>the composition picture rectangle;</li>
 *   <li>every camera's eye, target, motion path and frame corners;</li>
 *   <li>every non-actor layer's centre/bounds, coloured by type, with its name (and text content
 *       for text layers).</li>
 * </ul>
 *
 * <h2>Why the guides use {@link ReplayGuideSpace}</h2>
 * <p>The free/orbit picture is the finished composition raster painted into a letterboxed plate.
 * A 2D layer is screen-fixed inside that raster; a 3D layer was projected by the free camera before
 * the raster was blitted. Projecting every guide through the 3D camera (and drawing the comp frame
 * as a 3D quad) made 2D labels and the frame drift off the picture whenever the camera orbited.
 * {@link ReplayGuideSpace} uses the renderer's own {@code CompScene.project} and then maps the
 * resulting composition pixels through the exact plate rectangle, so "where the guide is" and
 * "where the rendered layer is" are one coordinate transform, not two.</p>
 *
 * <p>It is a guide, not a second renderer: no layer pixels are drawn, so the replay preview stays
 * the lightweight bbsae composition view. The user can turn it off with the normal guides/camera
 * gizmo display settings.</p>
 */
public final class ReplayCompositionOverlay
{
    private ReplayCompositionOverlay()
    {}

    public static void render(UIContext context, ReplayEditSession session, int x, int y, int width, int height)
    {
        render(context, session, x, y, width, height, true);
    }

    /**
     * @param showLayerBoxes true for the pure guide view. When the real composition scene/final
     *        frame is already drawn, its layer labels and bounding boxes are redundant (and read
     *        like duplicated text), so only the camera/comp-frame guides are kept.
     */
    public static void render(UIContext context, ReplayEditSession session, int x, int y, int width, int height,
                              boolean showLayerBoxes)
    {
        if (context == null || session == null || width < 32 || height < 32 || !CompPrefs.get().guides)
        {
            return;
        }

        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompDocument doc = panel == null ? null : panel.getDocument();
        CompProject project = panel == null ? null : panel.getProject();

        if (doc == null || project == null || doc.layers == null)
        {
            return;
        }

        CompLayer linked = linkedLayer(doc, session.replay());

        if (linked == null)
        {
            return;
        }

        float time = session.playhead() / ReplayTime.TICKS_PER_SECOND;
        float frame = time * Math.max(1F, doc.fps);
        CompViewportCamera camera = session.camera();
        Area plate = new Area(x, y, width, height);

        /*
         * The actor is drawn by the composition from the linked layer's transform, while the
         * gizmo/pick are drawn from the replay's world position through ReplaySceneSpace. Those
         * two agree only after UIReplayViewport aligned the anchor this frame (it does, for both
         * camera and free/orbit views). The guides therefore use pure composition geometry here
         * instead of shifting the whole frame by an actor-space offset: shifting made the comp
         * frame and the 2D labels move away from the raster they are supposed to describe.
         */
        ReplayGuideSpace guides = new ReplayGuideSpace(project, doc, camera, time, plate);

        context.batcher.clip(x, y, width, height, context);

        try
        {
            renderCompFrame(context, plate);
            renderCameras(context, guides, doc, frame, time);

            if (showLayerBoxes)
            {
                renderLayers(context, guides, doc, frame);
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private static CompLayer linkedLayer(CompDocument doc, mchorse.bbs_mod.film.replays.Replay replay)
    {
        if (replay == null)
        {
            return null;
        }

        for (CompLayer layer : doc.layers)
        {
            if (layer != null && replay.getId() != null && replay.getId().equals(layer.replayId))
            {
                return layer;
            }
        }

        return null;
    }

    /**
     * The composition frame in a free view is the picture rectangle, not a 3D quad: the whole
     * raster was already projected and blitted into this plate. Drawing its projected corners made
     * the yellow frame separate from the picture as soon as yaw/pitch changed.
     */
    private static void renderCompFrame(UIContext context, Area plate)
    {
        int color = 0x55FFC14D;

        context.batcher.outline(plate.x, plate.y, plate.ex(), plate.ey(), color);

        for (int i = 0; i < 4; i++)
        {
            float cx = (i == 1 || i == 2) ? plate.ex() : plate.x;
            float cy = i >= 2 ? plate.ey() : plate.y;

            context.batcher.box(cx - 2F, cy - 2F, cx + 2F, cy + 2F, 0xAAFFC14D);
        }
    }

    private static void renderCameras(UIContext context, ReplayGuideSpace guides, CompDocument doc,
                                      float frame, float time)
    {
        if (!CompPrefs.get().cameraGizmos)
        {
            return;
        }

        CompLayer active = doc.activeCamera(time);

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !"camera".equals(layer.type) || !layer.visible)
            {
                continue;
            }

            int color = layer == active ? 0xFFFFC14D : 0x99FFC14D;
            Vector3f eye = CompCamera.eyeOf(layer, frame, doc.height);
            Vector3f target = cameraTarget(layer, frame, eye);
            Vector2f eyeScreen = guides.projectComp(eye);
            Vector2f targetScreen = guides.projectComp(target);

            if (eyeScreen != null)
            {
                context.batcher.box(eyeScreen.x - 3F, eyeScreen.y - 3F,
                    eyeScreen.x + 3F, eyeScreen.y + 3F, 0xFF101014);
                context.batcher.box(eyeScreen.x - 2F, eyeScreen.y - 2F,
                    eyeScreen.x + 2F, eyeScreen.y + 2F, color);
                context.batcher.textShadow("🎥 " + name(layer), eyeScreen.x + 7F, eyeScreen.y - 4F, color);
            }

            if (targetScreen != null)
            {
                context.batcher.box(targetScreen.x - 3F, targetScreen.y - 3F,
                    targetScreen.x + 3F, targetScreen.y + 3F, 0xFF101014);
                context.batcher.box(targetScreen.x - 2F, targetScreen.y - 2F,
                    targetScreen.x + 2F, targetScreen.y + 2F, 0xCCFFC14D);
            }

            if (eyeScreen != null && targetScreen != null)
            {
                line(context, eyeScreen.x, eyeScreen.y, targetScreen.x, targetScreen.y, 0x66FFC14D);
            }

            /* Composition-plane corners projected into the free viewport; connecting the eye to
             * them communicates aim and framing without a second full composition render. */
            Vector2f[] frameCorners = new Vector2f[] {
                guides.projectComp(new Vector3f(0F, 0F, 0F)),
                guides.projectComp(new Vector3f(doc.width, 0F, 0F)),
                guides.projectComp(new Vector3f(doc.width, doc.height, 0F)),
                guides.projectComp(new Vector3f(0F, doc.height, 0F))
            };

            if (eyeScreen != null)
            {
                for (Vector2f corner : frameCorners)
                {
                    if (corner != null)
                    {
                        line(context, eyeScreen.x, eyeScreen.y, corner.x, corner.y, 0x33FFC14D);
                    }
                }
            }

            /* Motion path around the current replay time. */
            float from = Math.max(0F, layer.in);
            float to = Math.min(doc.duration, layer.out > layer.in ? layer.out : doc.duration);
            int samples = 32;

            for (int i = 0; i <= samples; i++)
            {
                float t = from + (to - from) * (i / (float) samples);
                Vector3f p = CompCamera.eyeOf(layer, t * Math.max(1F, doc.fps), doc.height);
                Vector2f screen = guides.projectComp(p);

                if (screen != null)
                {
                    context.batcher.box(screen.x - 1F, screen.y - 1F,
                        screen.x + 1F, screen.y + 1F, 0x77FFC14D);
                }
            }
        }
    }

    /**
     * The point the camera aims at. A BBS-style camera is flown with yaw/pitch and has no authored
     * target, so its stale target fields must not be treated as the aim point.
     */
    private static Vector3f cameraTarget(CompLayer layer, float frame, Vector3f eye)
    {
        boolean lookAt = layer.cameraStyle != CompLayer.CAMERA_BBS && layer.cameraLookAt;

        if (lookAt)
        {
            return new Vector3f(
                layer.scalarAt("cameraTargetX", layer.cameraTargetX, frame),
                layer.scalarAt("cameraTargetY", layer.cameraTargetY, frame),
                layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, frame)
            );
        }

        float yaw = (float) Math.toRadians(layer.scalarAt("rotationY", layer.rotationY, frame));
        float pitch = (float) Math.toRadians(layer.scalarAt("rotationX", layer.rotationX, frame));
        float dirX = (float) (Math.sin(yaw) * Math.cos(pitch));
        float dirY = (float) (-Math.sin(pitch));
        float dirZ = (float) (-Math.cos(yaw) * Math.cos(pitch));

        return new Vector3f(eye.x + dirX * 1000F, eye.y + dirY * 1000F, eye.z + dirZ * 1000F);
    }

    private static void renderLayers(UIContext context, ReplayGuideSpace guides, CompDocument doc, float frame)
    {
        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || "camera".equals(layer.type)
                || "adjustment".equals(layer.type) || "null".equals(layer.type)
                || "bbs".equals(layer.type) || layer.independent)
            {
                continue;
            }

            float halfW = Math.max(0F, layer.width) * 0.5F;
            float halfH = Math.max(0F, layer.height) * 0.5F;
            float left = -layer.width * layer.anchorX;
            float top = -layer.height * layer.anchorY;
            Vector2f a = guides.projectLayer(layer, left, top, 0F);
            Vector2f b = guides.projectLayer(layer, left + halfW * 2F, top, 0F);
            Vector2f c = guides.projectLayer(layer, left + halfW * 2F, top + halfH * 2F, 0F);
            Vector2f d = guides.projectLayer(layer, left, top + halfH * 2F, 0F);
            Vector2f center = guides.projectLayer(layer, left + halfW, top + halfH, 0F);

            if (center == null)
            {
                continue;
            }

            int color = layerColor(layer);

            if (a != null && b != null && c != null && d != null)
            {
                float minX = Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x));
                float minY = Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y));
                float maxX = Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x));
                float maxY = Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y));

                /* One axis-aligned outline instead of four dotted perspective edges: a guide must
                 * never cost hundreds of batcher draws per layer. */
                context.batcher.outline(minX, minY, maxX, maxY, (color & 0x00FFFFFF) | 0x55000000);
            }

            context.batcher.box(center.x - 2F, center.y - 2F, center.x + 2F, center.y + 2F, color);

            String label = name(layer);

            if ("text".equals(layer.type) && layer.text != null && !layer.text.isBlank())
            {
                String text = layer.text.replace('\n', ' ');

                if (text.length() > 18)
                {
                    text = text.substring(0, 18) + "…";
                }

                label += "  " + text;
            }

            context.batcher.textShadow(label, center.x + 6F, center.y - 4F, color);
        }
    }

    private static String name(CompLayer layer)
    {
        String name = layer.name;

        if (name == null || name.isBlank())
        {
            name = layer.type;
        }

        return name;
    }

    private static int layerColor(CompLayer layer)
    {
        return switch (layer.type)
        {
            case "text" -> 0xFF74D6FF;
            case "image", "footage" -> 0xFF7BE0A0;
            case "shape", "solid" -> 0xFFFFB36B;
            case "precomp" -> 0xFFC79BFF;
            default -> 0xFFB8C4D6;
        };
    }

    /** A one-pixel-ish screen line made of a thin rotated quad would need a matrix; dots are
     * enough for a guide and stay in the batcher's axis-aligned path. */
    private static void line(UIContext context, float x1, float y1, float x2, float y2, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = Math.min(24, Math.max(1, (int) (Math.max(Math.abs(dx), Math.abs(dy)) / 10F)));

        for (int i = 0; i <= steps; i++)
        {
            float t = i / (float) steps;
            float x = x1 + dx * t;
            float y = y1 + dy * t;

            context.batcher.box(x - 1F, y - 1F, x + 1F, y + 1F, color);
        }
    }
}
