package demo;

import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository {
  Order save(Order order);
  Order find(int id);
  Order find(String name);
}
