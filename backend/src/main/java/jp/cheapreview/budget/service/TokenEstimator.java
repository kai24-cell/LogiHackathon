package jp.cheapreview.budget.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import jp.cheapreview.budget.dto.BudgetPreview.Cost;
import jp.cheapreview.budget.dto.BudgetPreview.Counts;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {
  private final BudgetSettings settings;

  public TokenEstimator(BudgetSettings settings) {
    this.settings = settings;
  }

  /** Unicodeを排他的に数え、完全な送信材料を一度だけ上方向へ丸めて推定する。 */
  public Counts estimate(String material) {
    long ascii = 0, japanese = 0, other = 0;
    for (int point : material.codePoints().toArray()) {
      if (point <= 0x7f) ascii++;
      else if (japanese(point)) japanese++;
      else other++;
    }
    // T=ceil(a*N_ascii+j*N_ja+o*N_other)、単位tokens。改行もASCII、補助漢字・絵文字は1点。
    // a/j/oは暫定係数0.35/1.50/1.00。providerの実tokensではなく比較実験で今後調整する。
    var weighted =
        settings
            .asciiWeight()
            .multiply(BigDecimal.valueOf(ascii))
            .add(settings.japaneseWeight().multiply(BigDecimal.valueOf(japanese)))
            .add(settings.otherWeight().multiply(BigDecimal.valueOf(other)));
    return new Counts(ascii, japanese, other, ceil(weighted));
  }

  private boolean japanese(int point) {
    var script = Character.UnicodeScript.of(point);
    var block = Character.UnicodeBlock.of(point);
    return script == Character.UnicodeScript.HAN
        || script == Character.UnicodeScript.HIRAGANA
        || script == Character.UnicodeScript.KATAKANA
        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
  }

  /** 安全余裕は全文の推定量から毎回再計算し、概算ちょうどの予算は許可しない。 */
  public long margin(long tokens) {
    // M=max(ceil(rate*T),minMargin)、required=T+M。既定20%または512tokens。
    // required境界は許可、1token不足は不可。丸めは十進数の切上げで浮動小数の誤差を避ける。
    return Math.max(
        ceil(settings.marginRate().multiply(BigDecimal.valueOf(tokens))), settings.minMargin());
  }

  public long required(long tokens) {
    return Math.addExact(tokens, margin(tokens));
  }

  /** 入力と想定最大出力の料金を別々に計算し、未知モデルは無料にせずnullを返す。 */
  public Cost cost(String modelId, long inputTokens) {
    var price = settings.prices().get(modelId);
    if (price == null) return null;
    // 金額=(T_input*P_input+T_maxOutput*P_output)/1,000,000。通貨は設定値。
    // 出力上限は入力予算へ加算しない。安全余裕も実入力とは別で費用へ二重加算しない。
    // 6桁へ切上げた概算であり保証額ではない。思考・cache・翻訳は未計測/別課金で含まない。
    var input = amount(inputTokens, price.inputPerMillion());
    var output = amount(settings.maxOutputTokens(), price.outputPerMillion());
    return new Cost(
        price.currency(),
        price.inputPerMillion(),
        price.outputPerMillion(),
        price.checkedOn().toString(),
        input,
        output,
        input.add(output));
  }

  private BigDecimal amount(long tokens, BigDecimal price) {
    return price
        .multiply(BigDecimal.valueOf(tokens))
        .divide(BigDecimal.valueOf(1000000), 6, RoundingMode.CEILING);
  }

  private long ceil(BigDecimal number) {
    return number.setScale(0, RoundingMode.CEILING).longValueExact();
  }
}
