package sample;

/** API検証用の注文処理。解析するだけで実行しない。 */
public class OrderService {
  private final OrderRepository repository;

  public OrderService(OrderRepository repository) {
    this.repository = repository;
  }

  /** 数量が不正なら保存せず例外を返す。 */
  public long createOrder(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("Quantity must be positive");
    }

    return repository.save(quantity);
  }
}
