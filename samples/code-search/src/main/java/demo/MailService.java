package demo;

@Service
public class MailService {
  // メールの配信処理。注文登録とは関係しない。
  public String deliver(String address) { return address; }
}
