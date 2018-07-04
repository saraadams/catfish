package de.ofahrt.catfish.upload;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import de.ofahrt.catfish.model.server.PayloadParser;
import de.ofahrt.catfish.upload.MultipartBody.Part;
import de.ofahrt.catfish.utils.HttpContentType;

public final class IncrementalMultipartParser implements PayloadParser {

  private static enum State {
    PREAMBLE,
    END_BOUNDARY,
    END_BOUNDARY_EXPECT_HYPHEN,
    END_BOUNDARY_EXPECT_CR,
    END_BOUNDARY_EXPECT_LF,
    FIELD_NAME, FIELD_VALUE, FIELD_VALUE_EXPECT_LF,
    FIELD_NAME_OR_CONTINUATION_OR_END,
    HEADERS_END_EXPECT_LF,
    BODY_PART,
    EPILOGUE;
  }

  private State state = State.PREAMBLE;

  private final char[] boundary;
  private int searchPosition = 2;

  private StringBuffer elementBuffer = new StringBuffer();
  private String fieldName;
  private String fieldValue;
  private Map<String, String> fields = new TreeMap<>();

  private ByteArrayOutputStream bodyPart;
  private List<Part> parts = new ArrayList<>();
  private MalformedMultipartException error;

  public IncrementalMultipartParser(String contentType) {
    String[] parsedContentType;
    try {
      parsedContentType = HttpContentType.parseContentType(contentType);
    } catch (IllegalArgumentException e) {
      error = new MalformedMultipartException(e.getMessage());
      parsedContentType = null;
    }
    char[] foundBoundary = null;
    if (parsedContentType == null) {
      // We've already set an error above.
    } else if (!"multipart".equals(parsedContentType[0])) {
      error = new MalformedMultipartException("content type must be a multipart type");
    } else {
      for (int i = 2; i < parsedContentType.length; i += 2) {
        if ("boundary".equals(parsedContentType[i])) {
          if (foundBoundary != null) {
            error = new MalformedMultipartException("duplicate boundary specification in content type");
            continue;
          }
          try {
            foundBoundary = validateBoundary(parsedContentType[i + 1]);
          } catch (IllegalArgumentException e) {
            error = new MalformedMultipartException(e.getMessage());
          }
        }
      }
      if (foundBoundary == null) {
        error = new MalformedMultipartException("no boundary specification in content type");
      }
    }
    boundary = foundBoundary;
  }

  //     boundary := 0*69<bchars> bcharsnospace
  //
  //     bchars := bcharsnospace / " "
  //
  //     bcharsnospace := DIGIT / ALPHA / "'" / "(" / ")" /
  //                      "+" / "_" / "," / "-" / "." /
  //                      "/" / ":" / "=" / "?"
  static boolean isBoundaryCharacter(char c) {
    return ((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))
        || (c == '\'') || (c == '(') || (c == ')')
        || (c == '+') || (c == '_') || (c == ',') || (c == '-') || (c == '.')
        || (c == '/') || (c == ':') || (c == '=') || (c == '?')
        || (c == ' ');
  }

  static char[] validateBoundary(String boundary) {
    if (boundary.length() > 70) {
      throw new IllegalArgumentException("boundary specification too long");
    }
    //  "\r\n--"+
    char[] result = new char[4 + boundary.length()];
    result[0] = '\r';
    result[1] = '\n';
    result[2] = '-';
    result[3] = '-';
    for (int i = 0; i < boundary.length(); i++) {
      char c = boundary.charAt(i);
      if (!isBoundaryCharacter(c)) {
        throw new IllegalArgumentException("illegal character found in boundary specification");
      }
      result[4 + i] = c;
    }
    if (result[result.length-1] == ' ') {
      throw new IllegalArgumentException("boundary may not have a space at the end");
    }
    return result;
  }

  static boolean isNotFieldNameCharacter(char c) {
    return (c <= 32) || (c >= 127) || (c == ' ') || (c == ':');
  }

  static boolean isFieldNameCharacter(char c) {
    return !isNotFieldNameCharacter(c);
  }

  private static boolean isSpace(char c) {
    return (c == ' ') || (c == '\t');
  }

  private void trimAndAppendSpace() {
    if (elementBuffer.length() == 0) {
      // Trim all linear whitespace at the beginning.
    } else if (elementBuffer.charAt(elementBuffer.length() - 1) == ' ') {
      // Reduce all linear whitespace to a single space.
    } else {
      elementBuffer.append(' ');
    }
  }

  private void addField(String name, String value) {
    fields.put(name, value);
  }

  @Override
  public int parse(byte[] data, int offset, int length) {
    for (int i = 0; i < length; i++) {
      final char c = (char) (data[offset+i] & 0xff);
      if (c == boundary[searchPosition]) {
        searchPosition++;
      } else if (c == boundary[0]) {
        searchPosition = 1;
      } else {
        searchPosition = 0;
      }
      boolean isBoundaryMatch = searchPosition == boundary.length;
      if (isBoundaryMatch) searchPosition = 0;
      switch (state) {
        case PREAMBLE :
          if (isBoundaryMatch) {
            state = State.END_BOUNDARY_EXPECT_CR;
          }
          break;
        case END_BOUNDARY :
          if (c == '-') {
            state = State.END_BOUNDARY_EXPECT_HYPHEN;
          } else if (c == '\r') {
            state = State.END_BOUNDARY_EXPECT_LF;
          } else {
            error = new MalformedMultipartException("At end of boundary line: unexpected character.");
            return i;
          }
          break;
        case END_BOUNDARY_EXPECT_HYPHEN :
          if (c == '-') {
            state = State.EPILOGUE;
          } else {
            error = new MalformedMultipartException("At end of boundary line: unexpected character.");
            return i;
          }
          break;
        case END_BOUNDARY_EXPECT_CR :
          if (c == '\r') {
            state = State.END_BOUNDARY_EXPECT_LF;
          } else if (c == ' ') {
            // ; Composers MUST NOT generate
            // ; non-zero length transport
            // ; padding, but receivers MUST
            // ; be able to handle padding
            // ; added by message transports.
          } else {
            error = new MalformedMultipartException("At end of boundary line: unexpected character.");
            return i;
          }
          break;
        case END_BOUNDARY_EXPECT_LF :
          if (c == '\n') {
            state = State.FIELD_NAME;
            bodyPart = new ByteArrayOutputStream();
          } else {
            error = new MalformedMultipartException("At end of boundary line: CR not followed by LF.");
            return i;
          }
          break;
        case FIELD_NAME :
          if (c == ':') {
            if (elementBuffer.length() == 0) {
              error = new MalformedMultipartException("Expected field name, but ':' found.");
              return i;
            }
            fieldName = elementBuffer.toString();
            elementBuffer.setLength(0);
            state = State.FIELD_VALUE;
          } else if (c == '\r')  {
            if (elementBuffer.length() != 0) {
              error = new MalformedMultipartException("Unexpected end of line in field name.");
              return i;
            }
            state = State.HEADERS_END_EXPECT_LF;
          } else if (!isFieldNameCharacter(c)) {
            error = new MalformedMultipartException("Illegal character in field name.");
            return i;
          } else {
            elementBuffer.append(c);
          }
          break;
        case FIELD_VALUE :
          if (c == '\r') {
            int end = elementBuffer.length();
            while ((end > 0) && (elementBuffer.charAt(end-1) == ' ')) {
              end--;
            }
            elementBuffer.setLength(end);
            fieldValue = elementBuffer.toString();
            elementBuffer.setLength(0);
            state = State.FIELD_VALUE_EXPECT_LF;
          } else if (isSpace(c)) {
            trimAndAppendSpace();
          } else {
            elementBuffer.append(c);
          }
          break;
        case FIELD_VALUE_EXPECT_LF :
          if (c == '\n') {
            state = State.FIELD_NAME_OR_CONTINUATION_OR_END;
          } else {
            // A CR without LF is allowed, so we need to do some weird stuff here:
            elementBuffer.append(fieldValue);
            elementBuffer.append('\r');
            fieldValue = null;
            state = State.FIELD_VALUE;
            if (c == '\r') {
              fieldValue = elementBuffer.toString();
              elementBuffer.setLength(0);
              state = State.FIELD_VALUE_EXPECT_LF;
            } else if (isSpace(c)) {
              trimAndAppendSpace();
            } else {
              elementBuffer.append(c);
            }
          }
          break;
        case FIELD_NAME_OR_CONTINUATION_OR_END :
          if (isSpace(c)) {
            state = State.FIELD_VALUE;
            elementBuffer.append(fieldValue);
            trimAndAppendSpace();
            break;
          }

          addField(fieldName, fieldValue);
          fieldName = null;
          fieldValue = null;

          if (c == '\r') {
            state = State.HEADERS_END_EXPECT_LF;
          } else if (!isFieldNameCharacter(c)) {
            error = new MalformedMultipartException("Illegal character in header field");
            return i;
          } else {
            elementBuffer.setLength(0);
            state = State.FIELD_NAME;
            elementBuffer.append(c);
          }
          break;
        case HEADERS_END_EXPECT_LF :
          if (c == '\n') {
            state = State.BODY_PART;
          } else {
            error = new MalformedMultipartException("At end of field definition: CR not followed by LF.");
            return i;
          }
          break;
        case BODY_PART :
          if (isBoundaryMatch) {
            parts.add(new Part(fields));
            fields.clear();
            bodyPart = null;
            state = State.END_BOUNDARY;
          } else {
            bodyPart.write(c);
          }
          break;
        case EPILOGUE :
          break;
      }
    }
    return length;
  }

  @Override
  public boolean isDone() {
    return (state == State.EPILOGUE) || (error != null);
  }

  @Override
  public MultipartBody getParsedBody() throws MalformedMultipartException {
    if (error != null) {
      throw error;
    }
    return new MultipartBody(parts.toArray(new MultipartBody.Part[0]));
  }
}