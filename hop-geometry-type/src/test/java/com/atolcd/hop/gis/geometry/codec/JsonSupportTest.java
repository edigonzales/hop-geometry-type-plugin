package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSupportTest {
  @Test void preservesNumbersAndNestedNullsWithoutDoubleConversion() throws Exception {
    String input = "{\"id\":123456789012345678901234567890,\"decimal\":0.123456789012345678901234567890,\"nested\":[null,{\"x\":true}]}";
    Map<String,Object> object = JsonSupport.parseObject(input);
    assertThat(object.get("id")).isEqualTo(new BigInteger("123456789012345678901234567890"));
    assertThat(object.get("decimal")).isEqualTo(new BigDecimal("0.123456789012345678901234567890"));
    assertThat(JsonSupport.parse(JsonSupport.stringify(object))).isEqualTo(object);
  }
  @Test void strictSyntaxRejectsDuplicatesTrailingValuesAndNonJsonTypes() {
    for (String value : List.of("{\"x\":1,\"x\":2}","[1,]","{} {}","{x:1}","NaN",""))
      assertThatThrownBy(() -> JsonSupport.parse(value)).isInstanceOf(GeometryCodecException.class);
    assertThatThrownBy(() -> JsonSupport.stringify(Double.NaN)).isInstanceOf(GeometryCodecException.class);
    assertThatThrownBy(() -> JsonSupport.stringify(new Object())).isInstanceOf(GeometryCodecException.class);
  }
  @Test void pullReaderSupportsSkippingAndReadingOneFeatureAtATime() throws Exception {
    String input = "{\"features\":[{\"id\":1},{\"id\":2}],\"crs\":{\"type\":\"name\"}}";
    try (var reader = JsonSupport.open(new StringReader(input))) {
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.START_OBJECT);
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.PROPERTY_NAME);
      assertThat(reader.name()).isEqualTo("features");
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.START_ARRAY);
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.START_OBJECT);
      assertThat(reader.readValue()).isEqualTo(Map.of("id",BigInteger.ONE));
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.START_OBJECT);
      reader.skipValue();
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.END_ARRAY);
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.PROPERTY_NAME);
      assertThat(reader.name()).isEqualTo("crs");
      reader.next();
      assertThat(reader.readValue()).isEqualTo(Map.of("type","name"));
      assertThat(reader.next()).isEqualTo(JsonSupport.Token.END_OBJECT);
      assertThat(reader.next()).isNull();
    }
  }
  @Test void writerHonorsTheSameScalarAndNestingLimitsAsItsReader() {
    String large="x".repeat(16*1024*1024+1);
    assertThatThrownBy(()->JsonSupport.stringify(large)).hasMessageContaining("GC_LIMIT_EXCEEDED");
    assertThatThrownBy(()->JsonSupport.stringify(Map.of("x".repeat(50001),1))).hasMessageContaining("GC_LIMIT_EXCEEDED");
    Object nested=List.of();
    for(int i=0;i<128;i++) nested=List.of(nested);
    Object value=nested;
    assertThatThrownBy(()->JsonSupport.stringify(value)).hasMessageContaining("GC_LIMIT_EXCEEDED");
  }
  @Test void skippedValuesStillRejectDuplicateMembers() throws Exception {
    try (var reader = JsonSupport.open(new StringReader("{\"x\":1,\"x\":2}"))) {
      reader.next();
      assertThatThrownBy(reader::skipValue).isInstanceOf(GeometryCodecException.class);
    }
  }
}
