

package com.oak.parser;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.oak.parser.PdfModels.*;

public final class PdfParser {
  private final PdfBoxSpanExtractor extractor = new PdfBoxSpanExtractor();

  // Tweak these per your PDFs
  private static final float LINE_Y_TOLERANCE = 2.5f;     // points
  private static final float COLUMN_GAP_MIN = 35.0f;      // points
  private static final int HEADER_FOOTER_MIN_PAGES = 3;   // repeated lines threshold
  private static final float HEADER_ZONE_MARGIN = 52.0f;  // distance from page top
  private static final float FOOTER_ZONE_MARGIN = 52.0f;  // distance from page bottom
  private static final Pattern HEADING_NUMBERING =
      Pattern.compile("^\\s*(\\d+(\\.\\d+)*|[IVX]+|[A-Z])([\\.)])\\s+.*");

  public ParsedDocument parse(InputStream pdf) throws Exception {
    Map<Integer, Float> pageHeights = new HashMap<>();
    List<Span> spans = extractor.extract(pdf, pageHeights);
    int pageCount = spans.stream().mapToInt(Span::page).max().orElse(0);

    // 1) spans -> lines
    List<Line> lines = buildLines(spans);

    // 2) remove repeated header/footer lines
    Set<String> repeated = findRepeatedHeaderFooter(lines, pageCount, pageHeights);
    lines = lines.stream()
        .filter(l -> !repeated.contains(normalizeLineKey(l.text())))
        .collect(Collectors.toList());

    // 3) split into blocks (simple paragraph grouping) + classify headings
    Map<Integer, List<Line>> linesByPage = lines.stream().collect(Collectors.groupingBy(Line::page));
    List<ParsedPage> pages = new ArrayList<>(pageCount);

    for (int p = 1; p <= pageCount; p++) {
      List<Line> pageLines = new ArrayList<>(linesByPage.getOrDefault(p, List.of()));
      pageLines.sort(Comparator.comparing(Line::y).thenComparing(Line::xMin));

      // 4) detect columns and reorder lines in reading order
      pageLines = reorderReadingOrder(pageLines);

      List<Block> blocks = buildBlocks(pageLines);
      String pageText = blocks.stream()
          .map(Block::text)
          .filter(text -> text != null && !text.isBlank())
          .collect(Collectors.joining("\n"));
      pages.add(new ParsedPage(p, pageText, blocks));
    }

    return new ParsedDocument(pageCount, pages);
  }

  private List<Line> buildLines(List<Span> spans) {
    // group by page, then cluster by y
    Map<Integer, List<Span>> byPage = spans.stream().collect(Collectors.groupingBy(Span::page));
    List<Line> out = new ArrayList<>();

    for (var entry : byPage.entrySet()) {
      int page = entry.getKey();
      List<Span> pageSpans = new ArrayList<>(entry.getValue());

      // Sort by y then x (PDF y increases downward in this coordinate usage)
      pageSpans.sort(Comparator
          .comparing(Span::y)
          .thenComparing(Span::x));

      List<List<Span>> clusters = new ArrayList<>();
      for (Span s : pageSpans) {
        if (clusters.isEmpty()) {
          clusters.add(new ArrayList<>(List.of(s)));
          continue;
        }
        List<Span> last = clusters.get(clusters.size() - 1);
        float y0 = last.get(0).y();
        if (Math.abs(s.y() - y0) <= LINE_Y_TOLERANCE) {
          last.add(s);
        } else {
          clusters.add(new ArrayList<>(List.of(s)));
        }
      }

      for (List<Span> lineSpans : clusters) {
        lineSpans.sort(Comparator.comparing(Span::x));

        StringBuilder sb = new StringBuilder();
        float xMin = Float.MAX_VALUE, xMax = -Float.MAX_VALUE;
        float fontSum = 0f;
        int fontN = 0;

        Span prev = null;
        for (Span s : lineSpans) {
          String t = s.text();

          // Insert spaces with a font-relative threshold to better handle kerning/tracking PDFs.
          if (prev != null) {
            float gap = s.x() - (prev.x() + prev.w());
            float spaceThreshold = Math.max(1.5f, s.fontSize() * 0.20f);
            boolean startsAlphaNum = !t.isEmpty() && Character.isLetterOrDigit(t.charAt(0));
            boolean prevHasTrailingSpace = !prev.text().isEmpty() && prev.text().endsWith(" ");
            if (gap > spaceThreshold || (gap > 0.5f && !prevHasTrailingSpace && startsAlphaNum)) {
              sb.append(' ');
            }
          }

          sb.append(t);
          xMin = Math.min(xMin, s.x());
          xMax = Math.max(xMax, s.x() + s.w());
          if (s.fontSize() > 0) {
            fontSum += s.fontSize();
            fontN++;
          }
          prev = s;
        }

        String text = cleanupText(sb.toString());
        if (text.isBlank()) continue;

        float avgFont = fontN == 0 ? 0f : (fontSum / fontN);
        float y = lineSpans.get(0).y();
        out.add(new Line(page, y, xMin, xMax, avgFont, text));
      }
    }
    return out;
  }

  private Set<String> findRepeatedHeaderFooter(List<Line> lines, int pageCount, Map<Integer, Float> pageHeights) {
    // Repeated normalized line keys appearing on many pages in header/footer zones
    Map<String, Set<Integer>> keyPages = new HashMap<>();

    for (Line l : lines) {
      float pageHeight = pageHeights.getOrDefault(l.page(), 792.0f);
      boolean inHeaderFooterZone = (l.y() <= HEADER_ZONE_MARGIN) || (l.y() >= (pageHeight - FOOTER_ZONE_MARGIN));
      if (!inHeaderFooterZone) continue;

      String key = normalizeLineKey(l.text());
      if (key.isBlank()) continue;

      keyPages.computeIfAbsent(key, k -> new HashSet<>()).add(l.page());
    }

    int threshold = Math.min(pageCount, HEADER_FOOTER_MIN_PAGES);
    return keyPages.entrySet().stream()
        .filter(e -> e.getValue().size() >= threshold)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  private List<Line> reorderReadingOrder(List<Line> lines) {
    if (lines.isEmpty()) return lines;

    // Heuristic: if many lines exist in both left and right halves -> treat as two columns
    float xMin = lines.stream().map(Line::xMin).min(Float::compare).orElse(0f);
    float xMax = lines.stream().map(Line::xMax).max(Float::compare).orElse(0f);
    float mid = (xMin + xMax) / 2f;

    List<Line> left = new ArrayList<>();
    List<Line> right = new ArrayList<>();

    for (Line l : lines) {
      float center = (l.xMin() + l.xMax()) / 2f;
      if (center < mid) left.add(l); else right.add(l);
    }

    // If not really two columns, keep simple top-to-bottom.
    if (left.isEmpty() || right.isEmpty()) return lines;

    float leftMax = left.stream().map(Line::xMax).max(Float::compare).orElse(mid);
    float rightMin = right.stream().map(Line::xMin).min(Float::compare).orElse(mid);
    if ((rightMin - leftMax) < COLUMN_GAP_MIN) return lines;

    // Reading order: left column top->bottom then right column top->bottom
    left.sort(Comparator.comparing(Line::y).thenComparing(Line::xMin));
    right.sort(Comparator.comparing(Line::y).thenComparing(Line::xMin));

    List<Line> ordered = new ArrayList<>(lines.size());
    ordered.addAll(left);
    ordered.addAll(right);
    return ordered;
  }

  private List<Block> buildBlocks(List<Line> orderedLines) {
    if (orderedLines.isEmpty()) return List.of();

    // Estimate body font size as median
    List<Float> fonts = orderedLines.stream()
        .map(Line::avgFontSize)
        .filter(f -> f > 0)
        .sorted()
        .toList();
    float medianFont = fonts.isEmpty() ? 0f : fonts.get(fonts.size() / 2);

    List<Block> blocks = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    float yTop = orderedLines.get(0).y(), yBottom = orderedLines.get(0).y();
    float xMin = orderedLines.get(0).xMin(), xMax = orderedLines.get(0).xMax();
    int page = orderedLines.get(0).page();

    BlockType currentType = classifyLine(orderedLines.get(0), medianFont);

    for (int i = 0; i < orderedLines.size(); i++) {
      Line l = orderedLines.get(i);
      BlockType t = classifyLine(l, medianFont);

      boolean newBlock = false;
      if (t != currentType) newBlock = true;
      if (i > 0) {
        float dy = l.y() - orderedLines.get(i - 1).y();
        if (dy > 14.0f) newBlock = true; // paragraph spacing heuristic
      }

      if (newBlock && buf.length() > 0) {
        blocks.add(new Block(page, currentType, cleanupText(buf.toString()), yTop, yBottom, xMin, xMax));
        buf.setLength(0);
        currentType = t;
        yTop = l.y();
        xMin = l.xMin();
        xMax = l.xMax();
      }

      if (buf.length() > 0) buf.append('\n');
      buf.append(l.text());

      yBottom = l.y();
      xMin = Math.min(xMin, l.xMin());
      xMax = Math.max(xMax, l.xMax());
      page = l.page();
    }

    if (buf.length() > 0) {
      blocks.add(new Block(page, currentType, cleanupText(buf.toString()), yTop, yBottom, xMin, xMax));
    }
    return blocks;
  }

  private BlockType classifyLine(Line l, float medianFont) {
    String txt = l.text();
    if (txt.length() <= 80 && HEADING_NUMBERING.matcher(txt).matches()) return BlockType.HEADING;

    // font-based heading guess (works decently on filings)
    if (medianFont > 0 && l.avgFontSize() >= (medianFont * 1.20f) && txt.length() <= 120) {
      return BlockType.HEADING;
    }
    // ALL CAPS short lines are often headings
    if (txt.length() <= 60 && isMostlyUpper(txt)) return BlockType.HEADING;

    return BlockType.PARAGRAPH;
  }

  private static boolean isMostlyUpper(String s) {
    int letters = 0, upper = 0;
    for (char c : s.toCharArray()) {
      if (Character.isLetter(c)) {
        letters++;
        if (Character.isUpperCase(c)) upper++;
      }
    }
    return letters >= 6 && upper >= (int) (letters * 0.8);
  }

  private static String cleanupText(String s) {
    String t = Normalizer.normalize(s, Normalizer.Form.NFKC);
    // normalize whitespace
    t = t.replace('\u00A0', ' ');
    t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
    t = t.replaceAll(" *\\n *", "\n");
    return t.trim();
  }

  private static String normalizeLineKey(String s) {
    String t = cleanupText(s).toLowerCase(Locale.ROOT);
    // remove digits that vary by page (page numbers)
    t = t.replaceAll("\\b\\d+\\b", "#");
    // collapse punctuation
    t = t.replaceAll("[^a-z# ]+", "");
    t = t.replaceAll("\\s+", " ").trim();
    return t;
  }
}
