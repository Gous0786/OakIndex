import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.oak.parser.PdfParser;
import com.oak.parser.PdfModels;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PdfParserTest {

  @Test
  void parsesPdf() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("sample.pdf")) {
      assertNotNull(in, "sample.pdf not found in src/test/resources");

      PdfParser parser = new PdfParser();
      PdfModels.ParsedDocument doc = parser.parse(in);

      assertTrue(doc.pageCount() > 0);
      assertFalse(doc.pages().isEmpty());

      ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
      String json = om.writeValueAsString(doc);

      Path out = Path.of("target", "parsed-output.json");
      Files.createDirectories(out.getParent());
      Files.writeString(out, json);

      System.out.println("Parsed output written to: " + out.toAbsolutePath());
    }
  }
}