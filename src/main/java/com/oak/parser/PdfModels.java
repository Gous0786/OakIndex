package com.oak.parser;

import java.util.List;
import java.util.stream.Collectors;

public final class PdfModels {
  private PdfModels() {}

  public record Span(
      int page,
      String text,
      float x,
      float y,
      float w,
      float h,
      float fontSize,
      String fontName
  ) {}

  public record Line(
      int page,
      float y,
      float xMin,
      float xMax,
      float avgFontSize,
      String text
  ) {}

  public enum BlockType { HEADING, PARAGRAPH }

  public record Block(
      int page,
      BlockType type,
      String text,
      float yTop,
      float yBottom,
      float xMin,
      float xMax
  ) {}

  public record ParsedPage(
      int pageNumber,
      String text,
      List<Block> blocks
  ) {}

  public record ParsedDocument(
      int pageCount,
      List<ParsedPage> pages
  ) {
    public String toTaggedText() {
      StringBuilder sb = new StringBuilder();
      for (ParsedPage page : pages) {
        sb.append("<physical_index_").append(page.pageNumber()).append(">\n");
        if (page.text() != null && !page.text().isBlank()) {
          sb.append(page.text()).append('\n');
        }
        sb.append("<physical_index_").append(page.pageNumber()).append(">\n\n");
      }
      return sb.toString();
    }

    public String getTextForRange(int startPage, int endPage) {
      if (startPage > endPage) {
        return "";
      }
      return pages.stream()
          .filter(p -> p.pageNumber() >= startPage && p.pageNumber() <= endPage)
          .map(ParsedPage::text)
          .filter(text -> text != null && !text.isBlank())
          .collect(Collectors.joining("\n"));
    }
  }
}