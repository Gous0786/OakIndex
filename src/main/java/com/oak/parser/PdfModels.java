package com.oak.parser;

import java.util.List;

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
      List<Block> blocks
  ) {}

  public record ParsedDocument(
      int pageCount,
      List<ParsedPage> pages
  ) {}
}