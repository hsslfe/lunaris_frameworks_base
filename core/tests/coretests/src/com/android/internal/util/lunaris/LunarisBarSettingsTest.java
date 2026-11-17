/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.lunaris;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LunarisBarSettingsTest {
    @Test
    public void withValues_matchesSequentialOverridesWithoutChangingSource() {
        LunarisBarSettings source = LunarisBarSettings.parse(
                "{\"animation_speed\":150,\"haptics\":true,\"unrelated\":{\"value\":7}}");
        String original = source.toString();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("mini_shape", 5);
        overrides.put("haptics", false);
        overrides.put("caption", "unchanged animation");
        LunarisBarSettings sequential = source;
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            sequential = sequential.with(entry.getKey(), entry.getValue());
        }

        LunarisBarSettings result = source.withValues(overrides);

        assertEquals(sequential, result);
        assertEquals(original, source.toString());
        assertEquals(1.5f, result.getAnimationSpeed(), 0f);
        assertFalse(result.isHapticsEnabled());
    }

    @Test
    public void withValues_emptyOverridesReturnsSource() {
        LunarisBarSettings source = new LunarisBarSettings();
        assertSame(source, source.withValues(Collections.emptyMap()));
    }

    @Test
    public void withValues_nullRemovesKeyLikeWith() {
        LunarisBarSettings source = new LunarisBarSettings().with("mini_shape", 5);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("mini_shape", null);

        assertEquals(source.with("mini_shape", null), source.withValues(overrides));
        assertEquals(5, source.integer("mini_shape", 2, 0, 31));
    }

    @Test
    public void withValues_keepsNestedSourceValuesIndependent() throws Exception {
        JSONObject nested = new JSONObject().put("value", 7);
        LunarisBarSettings source = new LunarisBarSettings().with("nested", nested);
        LunarisBarSettings result = source.withValues(Collections.singletonMap("mini_shape", 5));

        nested.put("value", 9);

        assertEquals(7, new JSONObject(result.toString()).getJSONObject("nested").getInt("value"));
    }

    @Test
    public void withValues_invalidBatchLeavesSourceUntouched() {
        LunarisBarSettings source = new LunarisBarSettings();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("mini_shape", 5);
        overrides.put("invalid", Double.NaN);

        assertSame(source, source.withValues(overrides));
        assertEquals(2, source.integer("mini_shape", 2, 0, 31));
    }
}
