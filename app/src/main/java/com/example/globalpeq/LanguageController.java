package com.example.globalpeq;

import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class LanguageController {
    static final String LANGUAGE_EN = "en";
    static final String LANGUAGE_ZH = "zh";

    interface TextProvider {
        CharSequence getText();
    }

    interface RefreshAction {
        void refresh();
    }

    private final PresetRepository repository;
    private final List<RefreshAction> refreshActions = new ArrayList<>();
    private String currentLanguage;

    LanguageController(PresetRepository repository) {
        this.repository = repository;
        currentLanguage = normalize(repository.loadUiLanguage());
    }

    boolean isChinese() {
        return LANGUAGE_ZH.equals(currentLanguage);
    }

    String tr(String english, String chinese) {
        return isChinese() ? chinese : english;
    }

    String currentLanguage() {
        return currentLanguage;
    }

    String[] languageOptions() {
        return new String[]{"English", "中文"};
    }

    int selectedLanguageIndex() {
        return isChinese() ? 1 : 0;
    }

    boolean setLanguage(String language) {
        String normalized = normalize(language);
        if (normalized.equals(currentLanguage)) {
            return false;
        }
        currentLanguage = normalized;
        repository.saveUiLanguage(currentLanguage);
        refreshAll();
        return true;
    }

    void bindText(TextView view, TextProvider provider) {
        register(() -> view.setText(provider.getText()));
    }

    void bindRefresh(RefreshAction action) {
        register(action);
    }

    void refreshAll() {
        for (int i = 0; i < refreshActions.size(); i++) {
            refreshActions.get(i).refresh();
        }
    }

    private void register(RefreshAction action) {
        refreshActions.add(action);
        action.refresh();
    }

    private static String normalize(String language) {
        return LANGUAGE_ZH.equalsIgnoreCase(language) ? LANGUAGE_ZH : LANGUAGE_EN;
    }
}
