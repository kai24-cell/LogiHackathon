package example;

import org.springframework.stereotype.Service;

@Service
public class OrderService {
  // 注文を作成する、走査確認用の小さなサンプル。
  public String createOrder(String customer) {
    return "Order for " + customer;
  }
}
