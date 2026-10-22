package com.poc.gctune;

/**
 * 独立页面「其他」，从「个性化」入口进入。
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class MiscActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_MISC;
    }
}
