package jp.cheapreview.translation.service;

/** providerの本文・原因例外を持たない定型エラー。 */
public final class TranslationFailure extends RuntimeException {
  private final Integer httpStatus;

  public TranslationFailure(String code) {
    this(code, null);
  }

  public TranslationFailure(String code, Integer httpStatus) {
    super(code, null, false, false);
    this.httpStatus = httpStatus;
  }

  public Integer httpStatus() {
    return httpStatus;
  }
}
