package com.poc.gctune;

/**
 * 独立页面「图标」，从「个性化」入口进入。
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class IconsActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_ICONS;
    }
}
