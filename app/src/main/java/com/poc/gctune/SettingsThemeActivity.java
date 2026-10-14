package com.poc.gctune;

/**
 * Standalone «Системные Настройки» page, opened from the Кастомизация hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class SettingsThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_SETTINGS_THEME;
    }
}
