package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Caption “voice” presets shown as chips; full instruction strings are saved in {@link FacebookPostSchedulePrefs}.
 */
public final class FacebookCaptionTonePresets {

    private FacebookCaptionTonePresets() {}

    public static final class Item {
        public final int labelResId;
        public final int instructionResId;

        Item(int labelResId, int instructionResId) {
            this.labelResId = labelResId;
            this.instructionResId = instructionResId;
        }
    }

    /** Order matches chip order in the UI. */
    public static final Item[] ITEMS = new Item[]{
            new Item(R.string.fb_caption_tone_chip_friendly, R.string.fb_caption_tone_instr_friendly),
            new Item(R.string.fb_caption_tone_chip_professional, R.string.fb_caption_tone_instr_professional),
            new Item(R.string.fb_caption_tone_chip_playful, R.string.fb_caption_tone_instr_playful),
            new Item(R.string.fb_caption_tone_chip_urgent, R.string.fb_caption_tone_instr_urgent),
            new Item(R.string.fb_caption_tone_chip_minimal, R.string.fb_caption_tone_instr_minimal),
            new Item(R.string.fb_caption_tone_chip_inspirational, R.string.fb_caption_tone_instr_inspirational),
    };

    @NonNull
    public static String instructionAt(@NonNull Context context, int index) {
        if (index < 0 || index >= ITEMS.length) return "";
        return context.getString(ITEMS[index].instructionResId).trim();
    }

    /** Returns preset index if {@code savedTone} exactly matches that preset’s instruction, else -1. */
    public static int indexMatching(@NonNull Context context, @Nullable String savedTone) {
        if (savedTone == null) return -1;
        String t = savedTone.trim();
        if (t.isEmpty()) return -1;
        for (int i = 0; i < ITEMS.length; i++) {
            if (t.equals(context.getString(ITEMS[i].instructionResId).trim())) {
                return i;
            }
        }
        return -1;
    }
}
