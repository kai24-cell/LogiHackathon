package demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  public SecurityFilterChain chain(UnknownSecurityBuilder builder) {
    // 依存ライブラリなしで、未解決として残る参照を確認する。
    return builder.build();
  }
}
