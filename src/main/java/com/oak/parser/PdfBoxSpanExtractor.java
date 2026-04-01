package com.oak.parser;


import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.oak.parser.PdfModels.Span;

public final class PdfBoxSpanExtractor {

  public List<Span> extract(InputStream pdf, Map<Integer, Float> pageHeights) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      List<Span> spans = new ArrayList<>();

      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        pageHeights.put(i + 1, doc.getPage(i).getMediaBox().getHeight());
      }

      PDFTextStripper stripper = new PDFTextStripper() {
        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
          int page = getCurrentPageNo();
          for (TextPosition tp : textPositions) {
            String t = tp.getUnicode();
            if (t == null || t.isBlank()) continue;

            spans.add(new Span(
                page,
                t,
                tp.getXDirAdj(),
                tp.getYDirAdj(),
                tp.getWidthDirAdj(),
                tp.getHeightDir(),
                tp.getFontSizeInPt(),
                tp.getFont().getName()
            ));
          }
        }
      };

      // Helps, but column PDFs still need our own ordering later
      stripper.setSortByPosition(true);
      stripper.setStartPage(1);
      stripper.setEndPage(doc.getNumberOfPages());
      stripper.getText(doc);

      return spans;
    }
  }
}
