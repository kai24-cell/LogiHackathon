package sample;

public class OrderRepository {
  private long nextId = 1;

  public long save(int quantity) {
    return nextId++;
  }
}
