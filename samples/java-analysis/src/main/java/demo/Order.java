package demo;

import jakarta.persistence.Entity;

@Entity
public class Order {
  private final String name;

  public Order(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }
}
