package jp.cheapreview.budget.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 任意候補の関連度と冗長性のバランスを設定する。 */
@ConfigurationProperties("cheapreview.budget.mmr")
public record MmrSettings(@DefaultValue("0.7") double lambda) {
  public MmrSettings {
    if (!Double.isFinite(lambda) || lambda < 0 || lambda > 1) {
      throw new IllegalArgumentException(
          "cheapreview.budget.mmr.lambda must be finite and within [0, 1]");
    }
  }
}
