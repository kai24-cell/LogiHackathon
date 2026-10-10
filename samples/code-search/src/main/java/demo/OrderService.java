package demo;

@Service
public class OrderService {
  private final OrderRepository repository;

  public OrderService(OrderRepository repository) { this.repository = repository; }

  // 注文を保存する。入力した注文名の登録処理。
  public String create(String name) { return repository.save(name); }
}
