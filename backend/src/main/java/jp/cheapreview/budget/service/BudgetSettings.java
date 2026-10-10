package jp.cheapreview.budget.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 検証前の推定係数と料金設定。実モデルの料金は既定値として推測しない。 */
@ConfigurationProperties("cheapreview.budget")
public record BudgetSettings(
    @DefaultValue("0.35") BigDecimal asciiWeight,
    @DefaultValue("1.50") BigDecimal japaneseWeight,
    @DefaultValue("1.00") BigDecimal otherWeight,
    @DefaultValue("0.20") BigDecimal marginRate,
    @DefaultValue("512") long minMargin,
    @DefaultValue("2048") long maxOutputTokens,
    @DefaultValue("3") int maxMethodsPerFile,
    @DefaultValue("100") int maxPreviews,
    Map<String, Price> prices) {
  public record Price(
      BigDecimal inputPerMillion,
      BigDecimal outputPerMillion,
      String currency,
      LocalDate checkedOn,
      Long contextLimit) {}

  public BudgetSettings {
    positive(asciiWeight);
    positive(japaneseWeight);
    positive(otherWeight);
    positive(marginRate);
    if (minMargin < 1
        || minMargin > 100000
        || maxOutputTokens < 1
        || maxOutputTokens > 100000
        || maxMethodsPerFile < 1
        || maxMethodsPerFile > 100
        || maxPreviews < 1) throw new IllegalArgumentException("Invalid budget settings");
    prices = prices == null ? Map.of() : Map.copyOf(prices);
    prices.forEach(
        (id, price) -> {
          if (id.isBlank()
              || price == null
              || price.inputPerMillion() == null
              || price.outputPerMillion() == null
              || price.inputPerMillion().signum() < 0
              || price.outputPerMillion().signum() < 0
              || price.currency() == null
              || !price.currency().matches("[A-Z]{3}")
              || price.checkedOn() == null
              || (price.contextLimit() != null && price.contextLimit() <= maxOutputTokens))
            throw new IllegalArgumentException("Invalid model price settings");
        });
  }

  private static void positive(BigDecimal value) {
    if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.TEN) > 0)
      throw new IllegalArgumentException("Invalid coefficient");
  }
}
