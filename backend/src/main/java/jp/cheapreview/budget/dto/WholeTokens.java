package jp.cheapreview.budget.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** JSONの小数・文字列を整数へ丸めず拒否する入力予算専用の変換。 */
public final class WholeTokens extends JsonDeserializer<Long> {
  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
      return (Long) context.handleUnexpectedToken(Long.class, parser);
    return parser.getLongValue();
  }
}
