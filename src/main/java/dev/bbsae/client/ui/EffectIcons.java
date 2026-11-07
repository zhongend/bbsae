package dev.bbsae.client.ui;

import dev.bbsae.comp.CompEffectRegistry;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Small vector icons for the effect library rows.
 *
 * <p>The library used to be a plain text list; a 16 px icon per row makes the category readable at
 * a glance without adding a thumbnail pipeline. Icons are BBS's own atlas icons, chosen by effect
 * category, so they follow the UI scale and theme like every other control.</p>
 */
final class EffectIcons
{
    private EffectIcons()
    {}

    static Icon forDefinition(CompEffectRegistry.Definition definition)
    {
        if (definition == null)
        {
            return Icons.NONE;
        }

        String category = definition.category == null ? "" : definition.category;

        if (category.startsWith("AM · 调色") || category.contains("调色"))
        {
            return Icons.COLOR;
        }

        if (category.startsWith("AM · 变形") || category.contains("变形"))
        {
            return Icons.MORPH;
        }

        if (category.startsWith("AM · 风格化") || category.contains("风格化"))
        {
            return Icons.SHAPES;
        }

        if (category.contains("模糊") || category.contains("锐化"))
        {
            return Icons.SPHERE;
        }

        if (category.contains("生成"))
        {
            return Icons.CHECKBOARD;
        }

        if (category.contains("抠像") || category.contains("遮罩"))
        {
            return Icons.KEY;
        }

        if (category.contains("转场"))
        {
            return Icons.EXCHANGE;
        }

        if (category.contains("重复"))
        {
            return Icons.STRUCTURE;
        }

        if (category.contains("3D") || category.contains("三维"))
        {
            return Icons.CYLINDER;
        }

        if (category.contains("文字"))
        {
            return Icons.FONT;
        }

        if (category.contains("运动") || category.contains("模板"))
        {
            return Icons.CURVES;
        }

        if (category.contains("光"))
        {
            return Icons.LIGHT;
        }

        return Icons.BLOCK;
    }
}
