package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The density tiers and the tooltip vocabulary are pure data, so they can be pinned headlessly.
 * The rest of the UI needs a GL context; these two are what the layout math depends on.
 */
public class CompUiTest
{
    @Test
    public void autoDensityFollowsBbsGuiScale()
    {
        /* Small scale = few pixels per GUI unit is wrong: a small scale means MORE logical pixels,
         * so there is room for comfortable controls; a large scale needs compact ones to fit. */
        assertEquals(CompUi.DENSITY_COMFORTABLE, CompUi.autoDensity(1F));
        assertEquals(CompUi.DENSITY_COMFORTABLE, CompUi.autoDensity(2F));
        assertEquals(CompUi.DENSITY_NORMAL, CompUi.autoDensity(2.5F));
        assertEquals(CompUi.DENSITY_NORMAL, CompUi.autoDensity(3F));
        assertEquals(CompUi.DENSITY_COMPACT, CompUi.autoDensity(3.5F));
        assertEquals(CompUi.DENSITY_COMPACT, CompUi.autoDensity(4F));
    }

    @Test
    public void explicitTiersProduceSmallerMetrics()
    {
        CompUi.configure(CompUi.DENSITY_COMPACT);

        int compactControl = CompUi.control();
        int compactRow = CompUi.row();
        int compactLabel = CompUi.labelWidth();
        int compactLeft = CompUi.leftWidth();

        CompUi.configure(CompUi.DENSITY_NORMAL);

        assertTrue(CompUi.control() > compactControl, "normal must be taller than compact");
        assertTrue(CompUi.row() > compactRow);
        assertTrue(CompUi.labelWidth() > compactLabel);
        assertTrue(CompUi.leftWidth() > compactLeft);

        CompUi.configure(CompUi.DENSITY_COMFORTABLE);

        assertTrue(CompUi.control() > 16, "comfortable must be taller than the old fixed 16-18px");
        assertTrue(CompUi.leftWidth() > 190);
    }

    @Test
    public void compactTierFitsMoreInformation()
    {
        CompUi.configure(CompUi.DENSITY_COMPACT);

        assertTrue(CompUi.control() <= 14);
        assertTrue(CompUi.row() <= 16);
        assertTrue(CompUi.leftWidth() <= 152);
        assertTrue(CompUi.rightWidth() <= 268);
        assertTrue(CompUi.timelineHeight() <= 232);
    }

    @Test
    public void everyKnownLabelHasADetailedTooltip()
    {
        String[] labels = {
            "保存", "播放", "导出", "渲染", "设置", "关键帧", "吸附",
            "重命名…", "复制", "删除", "合成预渲染", "编组（空对象）",
            "左", "右", "上", "下", "水平中", "垂直中", "水平等距", "垂直等距",
            "重置摄像机", "设为活动摄像机", "添加样式…", "添加参数",
            "存为动画", "存为效果", "应用效果", "进入合成器", "创建并进入"
        };

        for (String label : labels)
        {
            String text = CompUi.describe(label);

            assertTrue(text.length() >= 8, "tooltip too short for " + label + ": " + text);
            assertNotEquals("点击执行：" + label, text, "no dedicated description for " + label);
        }
    }

    @Test
    public void unknownLabelsStillGetAnActionSentence()
    {
        String text = CompUi.describe("某个临时按钮");

        assertTrue(text.startsWith("点击执行："));
        assertTrue(text.contains("某个临时按钮"));
    }

    @Test
    public void densityPreferenceFallsBackToAuto()
    {
        assertEquals(CompUi.DENSITY_AUTO, CompUi.preferenceOf(null));

        CompProject project = new CompProject();

        assertEquals(CompUi.DENSITY_AUTO, CompUi.preferenceOf(project));

        project.settings.uiDensity = 3;

        assertEquals(CompUi.DENSITY_COMFORTABLE, CompUi.preferenceOf(project));
    }

    @Test
    public void tooltipPrefixLookupHandlesDynamicLabels()
    {
        /* "应用 电影青橙" should reuse the "应用" description and append the preset name. */
        String text = CompUi.describe("应用 电影青橙");

        assertTrue(text.contains("电影青橙"));
    }
}
