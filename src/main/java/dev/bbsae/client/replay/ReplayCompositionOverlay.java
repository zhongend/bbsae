package dev.bbsae.client.replay;

import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.ui.CompPrefs;
import dev.bbsae.client.ui.UICompositorPanel;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.ui.framework.UIContext;
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
 *   <li>the composition frame corners and edge lines;</li>
 *   <li>every camera's eye, target, motion path and frame corners;</li>
 *   <li>every non-actor layer's centre/bounds, coloured by type, with its name (and text content
 *       for text layers).</li>
 * </ul>
 *
 * <p>It is a guide, not a second renderer: no layer pixels are drawn, so the replay preview stays
 * the lightweight bbsae virtual 3D world. The user can turn it off with the normal guides/camera
 * gizmo display settings.</p>
 */
public final class ReplayCompositionOverlay
{
    private ReplayCompositionOverlay()
    {}

    public static void render(UIContext context, ReplayEditSession session, int x, int y, int width, int height)
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

        CompViewportCamera camera = session.camera();
        float time = session.playhead() / ReplayTime.TICKS_PER_SECOND;
        float frame = time * Math.max(1F, doc.fps);

        /* Evaluate the linked layer at the replay playhead, not its saved base transform: a
         * layer whose position is keyframed otherwise shifts the whole composition guide. */
        mchorse.bbs_mod.utils.pose.Transform linkedTransform = linked.transformAt(frame);
        Vector3f offset = session.space().toComp(linked.replayAnchorX, linked.replayAnchorY, linked.replayAnchorZ);
        offset.sub(linkedTransform.translate.x, linkedTransform.translate.y,
            linked.scalarAt("z", linked.z, frame));

        context.batcher.clip(x, y, width, height, context);

        try
        {
            renderCompFrame(context, camera, doc, offset, x, y);
            renderCameras(context, camera, doc, offset, frame, time, x, y);
            renderLayers(context, camera, doc, offset, frame, x, y);
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

    private static void renderCompFrame(UIContext context, CompViewportCamera camera, CompDocument doc,
                                        Vector3f offset, int ox, int oy)
    {
        float w = doc.width;
        float h = doc.height;
        Vector2f[] corners = new Vector2f[4];

        corners[0] = project(camera, new Vector3f(0F, 0F, 0F).add(offset), ox, oy);
        corners[1] = project(camera, new Vector3f(w, 0F, 0F).add(offset), ox, oy);
        corners[2] = project(camera, new Vector3f(w, h, 0F).add(offset), ox, oy);
        corners[3] = project(camera, new Vector3f(0F, h, 0F).add(offset), ox, oy);

        for (int i = 0; i < 4; i++)
        {
            Vector2f a = corners[i];
            Vector2f b = corners[(i + 1) % 4];

            if (a != null && b != null)
            {
                line(context, a.x, a.y, b.x, b.y, 0x55FFC14D);
            }
        }

        for (int i = 0; i < 4; i++)
        {
            if (corners[i] != null)
            {
                context.batcher.box(corners[i].x - 2F, corners[i].y - 2F,
                    corners[i].x + 2F, corners[i].y + 2F, 0xAAFFC14D);
            }
        }
    }

    private static void renderCameras(UIContext context, CompViewportCamera camera, CompDocument doc,
                                      Vector3f offset, float frame, float time, int ox, int oy)
    {
        if (!CompPrefs.get().cameraGizmos)
        {
            return;
        }

        float fps = Math.max(1F, doc.fps);
        CompLayer active = doc.activeCamera(time);

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !"camera".equals(layer.type) || !layer.visible)
            {
                continue;
            }

            int color = layer == active ? 0xFFFFC14D : 0x99FFC14D;
            Vector3f eye = CompCamera.eyeOf(layer, frame, doc.height);
            Vector3f target = new Vector3f(
                layer.scalarAt("cameraTargetX", layer.cameraTargetX, frame),
                layer.scalarAt("cameraTargetY", layer.cameraTargetY, frame),
                layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, frame)
            );
            Vector2f eyeScreen = project(camera, new Vector3f(eye).add(offset), ox, oy);
            Vector2f targetScreen = project(camera, new Vector3f(target).add(offset), ox, oy);

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

            /* Composition-plane corners projected into the replay viewport; connecting the eye to
             * them communicates aim and framing without a second full composition render. */
            Vector2f[] frameCorners = new Vector2f[] {
                project(camera, new Vector3f(0F, 0F, 0F).add(offset), ox, oy),
                project(camera, new Vector3f(doc.width, 0F, 0F).add(offset), ox, oy),
                project(camera, new Vector3f(doc.width, doc.height, 0F).add(offset), ox, oy),
                project(camera, new Vector3f(0F, doc.height, 0F).add(offset), ox, oy)
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
                Vector3f p = CompCamera.eyeOf(layer, t * fps, doc.height).add(offset);
                Vector2f screen = project(camera, p, ox, oy);

                if (screen != null)
                {
                    context.batcher.box(screen.x - 1F, screen.y - 1F,
                        screen.x + 1F, screen.y + 1F, 0x77FFC14D);
                }
            }
        }
    }

    private static void renderLayers(UIContext context, CompViewportCamera camera, CompDocument doc,
                                     Vector3f offset, float frame, int ox, int oy)
    {
        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || "camera".equals(layer.type)
                || "adjustment".equals(layer.type) || "null".equals(layer.type)
                || layer.independent)
            {
                continue;
            }

            mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(frame);
            float layerX = transform.translate.x;
            float layerY = transform.translate.y;
            float layerZ = layer.scalarAt("z", layer.z, frame);

            int color = layerColor(layer);
            Vector3f center = new Vector3f(layerX, layerY, layerZ).add(offset);
            Vector2f screen = project(camera, center, ox, oy);

            if (screen == null)
            {
                continue;
            }

            float halfW = Math.max(0F, layer.width) * 0.5F;
            float halfH = Math.max(0F, layer.height) * 0.5F;
            Vector2f a = project(camera, new Vector3f(layerX - halfW, layerY - halfH, layerZ).add(offset), ox, oy);
            Vector2f b = project(camera, new Vector3f(layerX + halfW, layerY - halfH, layerZ).add(offset), ox, oy);
            Vector2f c = project(camera, new Vector3f(layerX + halfW, layerY + halfH, layerZ).add(offset), ox, oy);
            Vector2f d = project(camera, new Vector3f(layerX - halfW, layerY + halfH, layerZ).add(offset), ox, oy);

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

            context.batcher.box(screen.x - 2F, screen.y - 2F, screen.x + 2F, screen.y + 2F, color);

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

            context.batcher.textShadow(label, screen.x + 6F, screen.y - 4F, color);
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

    private static Vector2f project(CompViewportCamera camera, Vector3f point, int ox, int oy)
    {
        Vector2f screen = camera.project(point);

        return screen == null ? null : new Vector2f(screen.x + ox, screen.y + oy);
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
