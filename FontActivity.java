package com.poc.gctune;

/**
 * 独立页面「字体」，从「个性化」入口进入。
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class FontActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_FONT;
    }
}
