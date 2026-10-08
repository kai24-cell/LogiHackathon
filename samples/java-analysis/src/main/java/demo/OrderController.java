package demo;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  // 注文作成をサービスへ委譲する。
  public Order create(String name) {
    return service.create(name);
  }
}
