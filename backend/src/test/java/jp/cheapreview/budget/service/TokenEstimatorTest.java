package jp.cheapreview.budget.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {
  static BudgetSettings settings(Map<String, BudgetSettings.Price> prices) {
    return new BudgetSettings(
        new BigDecimal("0.35"),
        new BigDecimal("1.50"),
        BigDecimal.ONE,
        new BigDecimal("0.20"),
        512,
        2048,
        3,
        100,
        prices);
  }

  @Test
  void unicodeClassificationIsExclusiveAndCountsSurrogatesOnce() {
    var estimate = new TokenEstimator(settings(Map.of())).estimate("A\nあ漢𠮷。Ａ😀é");
    assertEquals(2, estimate.ascii());
    assertEquals(5, estimate.japanese());
    assertEquals(2, estimate.other());
    assertEquals(11, estimate.tokens()); // ceil(2*.35+5*1.5+2)=11
    assertEquals(0, new TokenEstimator(settings(Map.of())).estimate("").tokens());
  }

  @Test
  void marginAndRoundingFollowExactDecimalBoundaries() {
    var estimator = new TokenEstimator(settings(Map.of()));
    assertEquals(512, estimator.margin(0));
    assertEquals(512, estimator.margin(2560));
    assertEquals(513, estimator.margin(2561));
    assertEquals(4800, estimator.required(4000));
    assertEquals(1, estimator.estimate("a").tokens());
    assertEquals(7, estimator.estimate("a".repeat(20)).tokens());
  }

  @Test
  void costsSeparateInputAndMaximumOutputAndUnknownIsNotFree() {
    var price =
        new BudgetSettings.Price(
            new BigDecimal("2"), new BigDecimal("4"), "USD", LocalDate.of(2026, 10, 10), null);
    var estimator = new TokenEstimator(settings(Map.of("fictional", price)));
    var cost = estimator.cost("fictional", 4000);
    assertEquals(new BigDecimal("0.008000"), cost.inputCost());
    assertEquals(new BigDecimal("0.008192"), cost.maximumOutputCost());
    assertEquals(new BigDecimal("0.016192"), cost.total());
    assertNull(estimator.cost("unknown", 4000));
  }

  @Test
  void invalidPricesAndCoefficientsFailAtConfigurationTime() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settings(
                Map.of(
                    "invalid",
                    new BudgetSettings.Price(
                        new BigDecimal("-1"), BigDecimal.ONE, "USD", LocalDate.now(), null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settings(
                Map.of(
                    "invalid",
                    new BudgetSettings.Price(BigDecimal.ONE, null, "USD", LocalDate.now(), null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settings(
                Map.of(
                    "invalid",
                    new BudgetSettings.Price(
                        BigDecimal.ONE, BigDecimal.ONE, "", LocalDate.now(), 1024L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BudgetSettings(
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal(".2"),
                512,
                2048,
                3,
                100,
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BudgetSettings(
                new BigDecimal("1e100"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal(".2"),
                512,
                2048,
                3,
                100,
                Map.of()));
  }

  @Test
  void exampleModelPricesBindFromPropertiesWithoutInventingDefaults() {
    var properties =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "cheapreview.budget.prices.fictional.input-per-million", "2",
                "cheapreview.budget.prices.fictional.output-per-million", "4",
                "cheapreview.budget.prices.fictional.currency", "USD",
                "cheapreview.budget.prices.fictional.checked-on", "2026-10-10",
                "cheapreview.budget.prices.fictional.context-limit", "32768"));
    var bound =
        new org.springframework.boot.context.properties.bind.Binder(properties)
            .bind(
                "cheapreview.budget",
                org.springframework.boot.context.properties.bind.Bindable.of(BudgetSettings.class))
            .get();
    assertEquals(new BigDecimal("2"), bound.prices().get("fictional").inputPerMillion());
    assertEquals(32768L, bound.prices().get("fictional").contextLimit());
    assertEquals(new BigDecimal("0.35"), bound.asciiWeight());
  }
}
