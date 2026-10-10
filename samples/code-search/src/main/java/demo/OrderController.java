package demo;

@RestController
public class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) { this.service = service; }

  public String submit(String name) { return service.create(name); }
}
