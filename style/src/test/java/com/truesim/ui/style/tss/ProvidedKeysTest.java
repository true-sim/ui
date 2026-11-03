package com.truesim.ui.style.tss;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleSheet;
import org.junit.jupiter.api.Test;

/** Keys of a {@link StyleKeyHolder} service on the class path compile without naming their holder. */
class ProvidedKeysTest {

    /** Found through {@code META-INF/services}. */
    public static final class Keys implements StyleKeyHolder {
        static final StyleKey<Float> PROVIDED = StyleKey.number("test-provided-number", 3, 0, 100);

        /** For the service loader. */
        public Keys() {
        }
    }

    @Test
    void aProvidersKeysAreKnownToEverySheet() {
        StyleSheet sheet = Tss.compileString("provided.tss", "panel { test-provided-number: 7; }");
        assertThat(sheet.toString()).contains("test-provided-number");
        assertThat(Keys.PROVIDED.defaultValue()).isEqualTo(3f);
    }
}
