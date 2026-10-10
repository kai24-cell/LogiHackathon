package demo;

@Repository
public class OrderRepository {
  // 注文名を保存する。解析用であり、データベースには接続しない。
  public String save(String name) { return name; }
}
