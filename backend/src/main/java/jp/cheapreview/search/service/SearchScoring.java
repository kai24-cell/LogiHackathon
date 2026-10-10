package jp.cheapreview.search.service;

import jp.cheapreview.search.dto.CodeSearch.Score;
import jp.cheapreview.search.dto.CodeSearch.Weights;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 比較実験で差し替えられるよう、暫定式と係数を検索処理から分離する。 */
@Component
public class SearchScoring {
  public static final String FORMULA_VERSION = "design-v02-provisional-1";
  private final Weights weights;

  public SearchScoring(
      @Value("${cheapreview.search.cosine-weight:0.50}") double cosine,
      @Value("${cheapreview.search.dependency-weight:0.30}") double dependency,
      @Value("${cheapreview.search.role-weight:0.20}") double role) {
    if (!Double.isFinite(cosine)
        || !Double.isFinite(dependency)
        || !Double.isFinite(role)
        || cosine < 0
        || dependency < 0
        || role < 0
        || Math.abs(cosine + dependency + role - 1) > 1e-9) {
      throw new IllegalArgumentException(
          "Search weights must be finite, non-negative and sum to one");
    }
    weights = new Weights(cosine, dependency, role);
  }

  /** 各項の寄与と総合スコアを同時に計算し、画面から順位の理由を追えるようにする。 */
  public Score score(double cosine, double dependency, double roleFit) {
    // 暫定式R=wC*C+wD*D+wR*F。CはL2正規化後のcosine、Dは2hop距離、Fは役割適合度。
    // C,D,Fは0〜1。係数は非負・合計1、初期値0.50/0.30/0.20は語彙を主に依存・役割で補う。
    // 一致なしC=0、選択起点なしD=0、役割根拠なしF=0。追加の正規化や除算は行わない。
    // これは設計書の暫定式であり、比較実験で今後調整する。確率・回答品質の保証値ではない。
    if (!valid(cosine) || !valid(dependency) || !valid(roleFit))
      throw new IllegalArgumentException("Score inputs must be within [0,1]");
    double c = weights.cosine() * cosine;
    double d = weights.dependency() * dependency;
    double r = weights.roleFit() * roleFit;

    return new Score(cosine, dependency, roleFit, c, d, r, c + d + r);
  }

  public Weights weights() {
    return weights;
  }

  private boolean valid(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }
}
