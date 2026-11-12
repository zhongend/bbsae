package dev.bbsae.client.replay;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.film.replays.tracks.TrackStyle;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the original replay dope-sheet rows for the replay mode panel.
 *
 * <p>This is the data half of {@code UIReplaysEditor.updateChannelsList} (§4.2 of the plan),
 * without the parts that need the original editor: the form catalog comes from
 * {@link TrackCatalog#forPart}, the row widgets from {@link UIReplaysEditorUtils#buildSheets}, and
 * the replay's own curated channels (position, rotation, states, hotbar, controls, velocity) are
 * collected under the same section headers, icons and colours the original uses. The panel then
 * puts the rows into a plain {@code UIKeyframeEditor}.</p>
 *
 * <p>No category filtering happens here: the replay mode shows every track at once, which is the
 * "all tracks" view of the original editor. The only filtering is the user's own disabled-track
 * configuration, which must be honoured or a track they switched off in the film editor would
 * reappear here.</p>
 */
public final class ReplayTrackBuilder
{
    private static final String[] GROUPS = {"position_rotation", "states", "hotbar", "equipment", "controls", "velocity"};
    private static final Icon[] GROUP_ICONS = {Icons.PLAYER, Icons.ACTION, TrackStyle.icon("item_slot_0"), Icons.ARMOR_CHESTPLATE, TrackStyle.icon("stick_lx"), Icons.FORWARD};
    private static final int[] GROUP_COLORS = {0x40bfff, Colors.ORANGE, Colors.YELLOW, Colors.BLUE, 0xb580ff, Colors.GREEN};

    private ReplayTrackBuilder()
    {}

    public static List<UIKeyframeSheet> build(Replay replay, String selectedPart)
    {
        List<UIKeyframeSheet> sheets = new ArrayList<>();

        if (replay == null)
        {
            return sheets;
        }

        String part = selectedPart == null ? "" : selectedPart;
        List<TrackDescriptor> catalog = TrackCatalog.forPart(replay.form.get(), replay.properties, part);

        collectCuratedSheets(replay, sheets);
        UIReplaysEditorUtils.buildSheets(catalog, sheets);
        removeDisabled(sheets);
        UIReplaysEditorUtils.pruneTree(sheets);

        return sheets;
    }

    /**
     * The replay's own placement/states tracks. Same grouping as the original editor, so a key
     * written by the 3D gizmo appears under "position / rotation" exactly where the user expects.
     */
    private static void collectCuratedSheets(Replay replay, List<UIKeyframeSheet> sheets)
    {
        for (int i = 0; i < GROUPS.length; i++)
        {
            String group = GROUPS[i];
            UIKeyframeSheet.Section section = new UIKeyframeSheet.Section(
                "replay_section/" + group,
                sectionTitle(group),
                GROUP_ICONS[i],
                GROUP_COLORS[i]
            );

            for (String key : ReplayKeyframes.CURATED_CHANNELS)
            {
                if (!groupOf(key).equals(group))
                {
                    continue;
                }

                KeyframeChannel channel = (KeyframeChannel) replay.keyframes.get(key);

                if (channel == null)
                {
                    continue;
                }

                UIKeyframeSheet sheet = new UIKeyframeSheet(TrackStyle.color(key), channel, null).icon(TrackStyle.icon(key));

                sheet.section = section;
                sheets.add(sheet);
            }
        }
    }

    private static IKey sectionTitle(String group)
    {
        return IKey.constant(switch (group)
        {
            case "position_rotation" -> "位置 / 旋转";
            case "states" -> "状态";
            case "hotbar" -> "快捷栏";
            case "equipment" -> "装备";
            case "controls" -> "操作";
            case "velocity" -> "速度";
            default -> group;
        });
    }

    private static String groupOf(String key)
    {
        if (key.startsWith("item_slot_") || key.equals("selected_slot") || key.equals("item_off_hand")) return "hotbar";
        if (key.startsWith("item_")) return "equipment";
        if (key.startsWith("stick_") || key.startsWith("trigger_") || key.startsWith("extra")) return "controls";

        return switch (key)
        {
            case "x", "y", "z", "pitch", "yaw", "headYaw", "bodyYaw" -> "position_rotation";
            case "vX", "vY", "vZ" -> "velocity";
            default -> "states";
        };
    }

    private static void removeDisabled(List<UIKeyframeSheet> sheets)
    {
        Set<String> disabled = BBSSettings.disabledSheets.get();

        sheets.removeIf((sheet) ->
        {
            String filterKey = sheet.getFilterKey();

            for (String key : disabled)
            {
                if (filterKey.equals(key) || sheet.id.equals(key) || sheet.id.endsWith("/" + key))
                {
                    return true;
                }
            }

            Form owner = UIReplaysEditor.getSheetForm(sheet);

            if (owner != null)
            {
                Set<String> ownerDisabled = owner.disabledTracks.get();

                return ownerDisabled.contains(Form.DISABLED_ALL) || ownerDisabled.contains(filterKey);
            }

            return false;
        });
    }

    /** The form path that a track belongs to, used to filter by body part. */
    public static String partOf(UIKeyframeSheet sheet)
    {
        if (sheet == null)
        {
            return "";
        }

        Form owner = UIReplaysEditor.getSheetForm(sheet);

        return owner == null ? "" : FormUtils.getPath(owner);
    }
}
