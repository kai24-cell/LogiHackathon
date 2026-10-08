package demo;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class OrderService {
  @Autowired private OrderRepository repository;

  // 注文名を保存する。外部サービスへ接続しない解析専用コード。
  public Order create(String name) {
    return repository.save(new Order(name));
  }

  public Order find(int id) {
    return repository.find(id);
  }

  public Order find(String name) {
    return repository.find(name);
  }
}
