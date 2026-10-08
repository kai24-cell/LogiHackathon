package demo;

public record OrderDto(String name) {
  public String label() {
    return name;
  }
}
