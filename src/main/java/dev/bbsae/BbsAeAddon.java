package dev.bbsae;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.BBSApi;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterCameraClipsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The common half of BBS Compositor.
 *
 * <p>Everything that touches the client lives in {@code dev.bbsae.client}; this class only
 * registers the composition clip and the addon's own asset namespace.</p>
 */
public class BbsAeAddon implements BBSAddonMod
{
    public static final String MOD_ID = "bbsae";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String CLIP_ID = MOD_ID + ":composition";

    /** The compositor's own 16x16 icon, shipped in the addon resource namespace. */
    public static final Icon ICON = new Icon(
        new Link(MOD_ID, "textures/gui/compositor_icon.png"),
        "compositor", 0, 0, 16, 16, 16, 16
    );

    @Subscribe
    public void onSourcePacks(RegisterSourcePacksEvent event)
    {
        BBSApi.requireVersion(MOD_ID, 2);
        event.registerAddon(MOD_ID, BbsAeAddon.class);
        LOGGER.info("BBS Compositor initialized");
    }

    @Subscribe
    public void onCameraClips(RegisterCameraClipsEvent event)
    {
        event.factory.register(
            new Link(MOD_ID, "composition"),
            dev.bbsae.comp.CompositionClip.class,
            new ClipFactoryData(ICON, 0x55AAFF)
        );
    }
}
