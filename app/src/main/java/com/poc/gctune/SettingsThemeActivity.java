package com.poc.gctune;

/**
 * 独立页面「系统设置」，从「个性化」入口进入。
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class SettingsThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_SETTINGS_THEME;
    }
}
