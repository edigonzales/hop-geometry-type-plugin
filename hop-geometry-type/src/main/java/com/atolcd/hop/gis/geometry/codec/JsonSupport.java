package com.atolcd.hop.gis.geometry.codec;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Strict, number-preserving JSON interchange without parser types in the public API.
 * JSON values are maps, lists, strings, booleans, null, BigInteger and BigDecimal.
 */
public final class JsonSupport {
  /** Maximum characters materialized for one value; streaming documents may be larger. */
  public static final long MAX_VALUE_CHARACTERS = 64L * 1024 * 1024;
  private static final JsonFactory FACTORY = JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128)
          .maxStringLength(16 * 1024 * 1024).maxNumberLength(10000).build())
      .build();

  private JsonSupport() {}

  /** Parses exactly one JSON value. Decimal numbers never pass through double. */
  public static Object parse(String json) throws GeometryCodecException {
    if (json == null) throw invalid("JSON text is null", null);
    if (json.length() > MAX_VALUE_CHARACTERS) throw limit();
    try (JsonReader reader = open(new StringReader(json))) {
      if (reader.next() == null) throw invalid("JSON text is empty", null);
      Object result = reader.readValue();
      if (reader.next() != null) throw invalid("Trailing JSON value", null);
      return result;
    }
  }

  /** Parses a JSON object and rejects scalar and array roots. */
  public static Map<String, Object> parseObject(String json) throws GeometryCodecException {
    Object value = parse(json);
    if (!(value instanceof Map<?, ?>)) throw invalid("Expected a JSON object", null);
    @SuppressWarnings("unchecked") Map<String,Object> object = (Map<String,Object>) value;
    return object;
  }

  /** Serializes JSON values, rejecting non-finite numbers and arbitrary Java objects. */
  public static String stringify(Object value) throws GeometryCodecException {
    StringWriter output = new BoundedStringWriter();
    try (JsonGenerator writer = FACTORY.createGenerator(ObjectWriteContext.empty(), output)) {
      write(writer, value, 0);
    } catch (GeometryCodecException e) { throw e;
    } catch (JsonLimitException e) { throw limit();
    } catch (RuntimeException e) { throw invalid("Unable to write JSON", e); }
    if (output.getBuffer().length() > MAX_VALUE_CHARACTERS) throw limit();
    return output.toString();
  }

  /** Opens a pull reader; closing it also closes its supplied reader. */
  public static JsonReader open(Reader input) throws GeometryCodecException {
    try { return new JsonReader(FACTORY.createParser(ObjectReadContext.empty(), input)); }
    catch (RuntimeException e) { throw invalid("Unable to open JSON reader", e); }
  }

  /** Parser-neutral token names for streaming object members and array entries. */
  public enum Token {
    START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, PROPERTY_NAME,
    STRING, INTEGER, DECIMAL, TRUE, FALSE, NULL
  }

  /**
   * Pull reader. After next() points to a value, readValue()/skipValue() leave the
   * cursor on that value's final token. Call next() to advance to its next sibling.
   */
  public static final class JsonReader implements AutoCloseable {
    private final JsonParser parser;
    private JsonReader(JsonParser parser) { this.parser = parser; }

    public Token next() throws GeometryCodecException {
      try { return token(parser.nextToken()); }
      catch (RuntimeException e) { throw invalid("Malformed JSON", e); }
    }
    public Token current() { return token(parser.currentToken()); }
    public String name() { return parser.currentName(); }

    public Object readValue() throws GeometryCodecException {
      long start = parser.currentTokenLocation().getCharOffset();
      try {
        Object value=read(parser,start,0);
        if (parser.currentLocation().getCharOffset()-start>MAX_VALUE_CHARACTERS) throw limit();
        return value;
      }
      catch (GeometryCodecException e) { throw e;
      } catch (RuntimeException e) { throw invalid("Malformed JSON value", e); }
    }
    public void skipValue() throws GeometryCodecException {
      if (parser.currentToken() == null) throw invalid("No current JSON value", null);
      try { parser.skipChildren(); }
      catch (RuntimeException e) { throw invalid("Malformed JSON value", e); }
    }
    @Override public void close() { parser.close(); }
  }

  private static Object read(JsonParser parser, long start, int depth)
      throws GeometryCodecException {
    if (depth > 128 || parser.currentLocation().getCharOffset() - start > MAX_VALUE_CHARACTERS)
      throw limit();
    JsonToken token = parser.currentToken();
    if (token == null) throw invalid("Unexpected end of JSON", null);
    return switch (token) {
      case START_OBJECT -> {
        Map<String,Object> result = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken() != JsonToken.PROPERTY_NAME)
            throw invalid("Expected JSON property", null);
          String name = parser.currentName();
          parser.nextToken();
          result.put(name, read(parser, start, depth + 1));
        }
        yield result;
      }
      case START_ARRAY -> {
        List<Object> result = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY)
          result.add(read(parser, start, depth + 1));
        yield result;
      }
      case VALUE_STRING -> parser.getString();
      case VALUE_NUMBER_INT -> parser.getBigIntegerValue();
      case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw invalid("Expected JSON value", null);
    };
  }

  private static Token token(JsonToken value) {
    if (value == null) return null;
    return switch (value) {
      case START_OBJECT -> Token.START_OBJECT;
      case END_OBJECT -> Token.END_OBJECT;
      case START_ARRAY -> Token.START_ARRAY;
      case END_ARRAY -> Token.END_ARRAY;
      case PROPERTY_NAME -> Token.PROPERTY_NAME;
      case VALUE_STRING -> Token.STRING;
      case VALUE_NUMBER_INT -> Token.INTEGER;
      case VALUE_NUMBER_FLOAT -> Token.DECIMAL;
      case VALUE_TRUE -> Token.TRUE;
      case VALUE_FALSE -> Token.FALSE;
      case VALUE_NULL -> Token.NULL;
      default -> throw new IllegalStateException("Unsupported JSON token");
    };
  }

  private static void write(JsonGenerator writer, Object value, int depth)
      throws GeometryCodecException {
    if (depth > 128 || (depth == 128 && (value instanceof Map<?,?> || value instanceof List<?>))) throw limit();
    if (value == null) writer.writeNull();
    else if (value instanceof String s) {
      if(s.length()>16*1024*1024) throw limit();
      writer.writeString(s);
    }
    else if (value instanceof Boolean b) writer.writeBoolean(b);
    else if (value instanceof BigDecimal n) {
      if(n.toString().length()>10000) throw limit();
      writer.writeNumber(n);
    }
    else if (value instanceof BigInteger n) {
      if(n.toString().length()>10000) throw limit();
      writer.writeNumber(n);
    }
    else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
      writer.writeNumber(((Number)value).longValue());
    else if (value instanceof Float || value instanceof Double) {
      double number = ((Number)value).doubleValue();
      if (!Double.isFinite(number)) throw invalid("Non-finite JSON number", null);
      writer.writeNumber(number);
    } else if (value instanceof Map<?,?> map) {
      writer.writeStartObject();
      for (Map.Entry<?,?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw invalid("JSON object key must be a string", null);
        if(key.length()>50000) throw limit();
        writer.writeName(key); write(writer, entry.getValue(), depth + 1);
      }
      writer.writeEndObject();
    } else if (value instanceof List<?> list) {
      writer.writeStartArray();
      for (Object element : list) write(writer, element, depth + 1);
      writer.writeEndArray();
    } else throw invalid("Unsupported JSON value type: " + value.getClass().getName(), null);
  }

  private static final class JsonLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  private static final class BoundedStringWriter extends StringWriter {
    private void reserve(int length) {
      if ((long)getBuffer().length() + length > MAX_VALUE_CHARACTERS) throw new JsonLimitException();
    }
    @Override public void write(int value) { reserve(1);super.write(value); }
    @Override public void write(char[] value, int offset, int length) { reserve(length);super.write(value,offset,length); }
    @Override public void write(String value, int offset, int length) { reserve(length);super.write(value,offset,length); }
  }

  private static GeometryCodecException invalid(String message, Throwable cause) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, message, -1, cause);
  }
  private static GeometryCodecException limit() {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_LIMIT_EXCEEDED, "JSON value exceeds materialization limit");
  }
}
