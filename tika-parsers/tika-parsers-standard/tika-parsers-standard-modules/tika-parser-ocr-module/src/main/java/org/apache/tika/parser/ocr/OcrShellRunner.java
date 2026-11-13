package org.apache.tika.parser.ocr;

import java.io.File;
import java.io.IOException;
import java.lang.Runtime;

final class OcrShellRunner {

    private OcrShellRunner() {
    }

    static Process start(TesseractOCRConfig defaults, File input, File output,
                         TesseractOCRConfig config) throws IOException {
        String language = config.getLanguage();
        Runtime r = Runtime.getRuntime();
        return r.exec("/bin/sh -c tesseract" + language);
    }
}
