package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.GeometryCodecErrorCode;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small per-read tokenizer for WKT. It deliberately does not use StreamTokenizer defaults. */
final class WktTokenCursor {
  enum Kind {
    WORD,
    NUMBER,
    NAN,
    LPAREN,
    RPAREN,
    COMMA,
    EOF
  }

  record Token(Kind kind, String text, int offset) {}

  private static final Pattern NUMBER =
      Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

  private final String text;
  private int position;
  private Token current;

  WktTokenCursor(String text) {
    this.text = text;
  }

  Token current() throws GeometryCodecException {
    if (current == null) {
      current = scan();
    }
    return current;
  }

  Token advance() throws GeometryCodecException {
    current = scan();
    return current;
  }

  boolean is(Kind kind) throws GeometryCodecException {
    return current().kind() == kind;
  }

  boolean wordIs(String word) throws GeometryCodecException {
    Token token = current();
    return token.kind() == Kind.WORD && token.text().equalsIgnoreCase(word);
  }

  String expectWord(String role) throws GeometryCodecException {
    Token token = current();
    if (token.kind() != Kind.WORD) {
      throw invalid(role + " expected", token.offset());
    }
    advance();
    return token.text();
  }

  void expect(Kind kind, String role) throws GeometryCodecException {
    Token token = current();
    if (token.kind() != kind) {
      throw invalid(role + " expected", token.offset());
    }
    advance();
  }

  double number(String role) throws GeometryCodecException {
    Token token = current();
    if (token.kind() != Kind.NUMBER) {
      throw invalid(role + " expected", token.offset());
    }
    advance();
    try {
      double value = Double.parseDouble(token.text());
      if (!Double.isFinite(value)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
            "Number is not finite",
            token.offset());
      }
      return value;
    } catch (NumberFormatException e) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_WKT, "Invalid number", token.offset(), e);
    }
  }

  private Token scan() throws GeometryCodecException {
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
    if (position >= text.length()) {
      return new Token(Kind.EOF, "", position);
    }
    int start = position;
    char c = text.charAt(position);
    switch (c) {
      case '(' -> {
        position++;
        return token(Kind.LPAREN, start);
      }
      case ')' -> {
        position++;
        return token(Kind.RPAREN, start);
      }
      case ',' -> {
        position++;
        return token(Kind.COMMA, start);
      }
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '-', '.' -> {
        Matcher matcher = NUMBER.matcher(text);
        matcher.region(position, text.length());
        if (!matcher.lookingAt()) {
          throw invalid("Invalid number", start);
        }
        position = matcher.end();
        return new Token(Kind.NUMBER, matcher.group(), start);
      }
      default -> {
        if (isWordStart(c)) {
          position++;
          while (position < text.length() && isWordPart(text.charAt(position))) {
            position++;
          }
          String word = text.substring(start, position);
          Kind kind = word.equalsIgnoreCase("NaN") ? Kind.NAN : Kind.WORD;
          return new Token(kind, word, start);
        }
        throw invalid("Unexpected character", start);
      }
    }
  }

  private Token token(Kind kind, int offset) {
    return new Token(kind, text.substring(offset, position), offset);
  }

  private static boolean isWordStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  private static boolean isWordPart(char c) {
    return isWordStart(c) || (c >= '0' && c <= '9') || c == '_';
  }

  private static GeometryCodecException invalid(String message, int offset) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_WKT, message, offset);
  }
}
