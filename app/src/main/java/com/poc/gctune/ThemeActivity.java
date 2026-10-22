package com.poc.gctune;

/**
 * 独立页面「主题」，从「个性化」入口进入。
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class ThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_THEME;
    }
}
