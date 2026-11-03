package com.truesim.ui.fonts.cjk;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.text.FontChain;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.lang.Character.UnicodeScript;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundledCjkFontsTest {

    @Test
    void hanTakesSimplifiedChineseAndKanaJapanese() {
        try (NativeTextBackend backend = new NativeTextBackend()) {
            FontRegistry fonts = BundledCjkFonts.registry(backend);
            FontChain chain = fonts.chain(List.of("sans-serif"), 400, false);
            assertThat(chain.faceFor('好', UnicodeScript.HAN).source().family()).isEqualTo("Noto Sans SC");
            assertThat(chain.faceFor('こ', UnicodeScript.HIRAGANA).source().family()).isEqualTo("Noto Sans JP");
            assertThat(chain.faceFor('カ', UnicodeScript.KATAKANA).source().family()).isEqualTo("Noto Sans JP");
            assertThat(chain.faceFor('ㄅ', UnicodeScript.BOPOMOFO).source().family()).isEqualTo("Noto Sans SC");
            assertThat(getClass().getClassLoader().getResource("com/truesim/ui/fonts/cjk/OFL-NotoSansCJK.txt"))
                .isNotNull();
        }
    }
}
