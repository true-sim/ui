package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import java.util.HashMap;
import java.util.Map;

/** {@link LayoutStore#inMemory()}. */
@AllowAllocation("layouts are saved on user action")
final class MemoryLayoutStore implements LayoutStore {

    private final Map<String, String> texts = new HashMap<>();

    @Override
    public synchronized String load(String key) {
        return texts.get(key);
    }

    @Override
    public synchronized void save(String key, String text) {
        texts.put(key, text);
    }
}
